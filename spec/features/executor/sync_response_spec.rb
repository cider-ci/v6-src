require 'spec_helper'

feature 'Sync response shape and dispatch limits' do
  include_context 'with executor api'

  def sync(body = {})
    executor_api(:post, '/executor/sync', {available_load: 4.0}.merge(body))
  end

  scenario 'sync echoes back trials_being_processed from the request' do
    fake_ids = [SecureRandom.uuid, SecureRandom.uuid]
    _code, body = sync(trials_being_processed: fake_ids)

    expect(body['trials_being_processed']).to eq fake_ids
  end

  scenario 'sync returns empty arrays when nothing is pending or aborting' do
    _code, body = sync

    expect(body['trials_to_execute']).to eq []
    expect(body['trials_to_abort']).to   eq []
  end

  scenario 'dispatched trial response includes git_url and patch_path' do
    job_id   = seed_job
    task_id  = seed_task(job_id, state: 'pending')
    _trial   = seed_trial(task_id, state: 'pending')

    _code, body = sync(available_load: 4.0)
    trial = body['trials_to_execute'].first

    expect(trial).not_to be_nil
    expect(trial['git_url']).to   match(%r{^http://.*?/projects/#{project_id}/git$})
    expect(trial['patch_path']).to match(%r{^/executor/trials/[0-9a-f-]+$})
    expect(trial['task_id']).not_to be_nil
    expect(trial['job_id']).not_to  be_nil
  end

  def executor_id
    database[:executors].where(token_part: @executor_token[0, 8]).first[:id]
  end

  def assign!(trial_id, state:, dispatched_seconds_ago:)
    database[:trials].where(id: trial_id)
      .update(state: state, executor_id: executor_id,
              dispatched_at: Time.now.utc - dispatched_seconds_ago, updated_at: Time.now.utc)
  end

  # The sync response never reached the executor (timeout, restart): the
  # trials stay 'dispatching' on the server while the executor reports them
  # as unknown. Legacy-style reconciliation at the next sync.
  scenario 'dispatched trials the executor does not report are pending again and dispatched anew' do
    job_id = seed_job
    ids = 2.times.map { seed_trial(seed_task(job_id, state: 'pending'), state: 'pending') }
    _code, body = sync(available_load: 4.0, trials_being_processed: [])
    expect(body['trials_to_execute'].map { |t| t['id'] }).to match_array ids.map(&:to_s)

    # too recent to be lost: the executor may still be starting them
    _code, body = sync(available_load: 4.0, trials_being_processed: [])
    expect(body['trials_to_execute']).to eq []

    ids.each { |id| assign!(id, state: 'dispatching', dispatched_seconds_ago: 60) }
    _code, body = sync(available_load: 4.0, trials_being_processed: [])
    expect(body['trials_to_execute'].map { |t| t['id'] }).to match_array ids.map(&:to_s)
    expect(database[:trials].where(state: 'dispatching').count).to eq 2

    # reported ones are left alone
    ids.each { |id| assign!(id, state: 'dispatching', dispatched_seconds_ago: 60) }
    _code, body = sync(available_load: 4.0, trials_being_processed: ids.map(&:to_s))
    expect(body['trials_to_execute']).to eq []
    expect(database[:trials].where(id: ids, state: 'dispatching').count).to eq 2
  end

  scenario 'running trials the executor does not know are lost: defective, aborting ones aborted' do
    job_id = seed_job
    executing = seed_trial(seed_task(job_id, state: 'executing'), state: 'executing')
    aborting  = seed_trial(seed_task(job_id, state: 'executing'), state: 'aborting')
    fresh     = seed_trial(seed_task(job_id, state: 'executing'), state: 'executing')
    assign!(executing, state: 'executing', dispatched_seconds_ago: 60)
    assign!(aborting,  state: 'aborting',  dispatched_seconds_ago: 60)
    assign!(fresh,     state: 'executing', dispatched_seconds_ago: 5)

    sync(available_load: 0.0, trials_being_processed: [])
    expect(database[:trials].where(id: executing).first[:state]).to eq 'defective'
    expect(database[:trials].where(id: executing).first[:error]).to match(/does not know it/)
    expect(database[:trials].where(id: aborting).first[:state]).to eq 'aborted'
    expect(database[:trials].where(id: fresh).first[:state]).to eq 'executing'
    # propagated: the task is retried (a new pending trial), no longer executing
    task_id = database[:trials].where(id: executing).first[:task_id]
    expect(database[:tasks].where(id: task_id).first[:state]).not_to eq 'executing'
    expect(database[:trials].where(task_id: task_id).count).to eq 2
  end

  scenario 'the server never assigns more than max_load, whatever the executor reports' do
    database[:executors].where(id: executor_id).update(max_load: 2.0)
    job_id = seed_job
    2.times { assign!(seed_trial(seed_task(job_id), state: 'dispatching'), state: 'dispatching', dispatched_seconds_ago: 1) }
    3.times { seed_trial(seed_task(job_id, state: 'pending'), state: 'pending') }

    # a restarted executor reports everything free
    _code, body = sync(available_load: 2.0, max_load: 2.0, trials_being_processed: [])
    expect(body['trials_to_execute']).to eq []

    # one of the two finishes: room for one
    done = database[:trials].where(executor_id: executor_id, state: 'dispatching').first[:id]
    database[:trials].where(id: done).update(state: 'passed')
    _code, body = sync(available_load: 2.0, max_load: 2.0, trials_being_processed: [])
    expect(body['trials_to_execute'].length).to eq 1
  end

  # The server ended the trial (here: the 60 minutes timeout) while the
  # executor still runs it: nothing the executor reports revives it, and the
  # next sync tells the executor to kill its processes.
  scenario 'a trial the server has ended stays ended and is told to abort' do
    job_id = seed_job
    trial  = seed_trial(seed_task(job_id, state: 'executing'), state: 'executing')
    assign!(trial, state: 'executing', dispatched_seconds_ago: 60)
    database[:trials].where(id: trial).update(state: 'defective', error: 'Execution timed out after 60 minutes',
                                              finished_at: Time.now.utc)

    code, body = executor_api(:patch, "/executor/trials/#{trial}", {state: 'executing', scripts_results: {}})
    expect(code).to eq 409
    expect(body['state']).to eq 'defective'
    expect(database[:trials].where(id: trial).first[:state]).to eq 'defective'

    _code, body = sync(available_load: 0.0, trials_being_processed: [trial.to_s])
    expect(body['trials_to_abort']).to eq [trial.to_s]

    # the executor's final report after killing: ignored, the state is final
    code, _body = executor_api(:patch, "/executor/trials/#{trial}", {state: 'aborted'})
    expect(code).to eq 409
    expect(database[:trials].where(id: trial).first[:state]).to eq 'defective'
  end

  scenario 'available_load caps the number of dispatched trials' do
    job_id = seed_job
    3.times do
      task_id = seed_task(job_id, state: 'pending')
      seed_trial(task_id, state: 'pending')
    end

    # available_load=1.5 → limit = int(1.5) = 1 → dispatch at most 1 trial
    _code, body = sync(available_load: 1.5)

    expect(body['trials_to_execute'].length).to eq 1
    expect(database[:trials].where(state: 'dispatching').count).to eq 1
  end
end
