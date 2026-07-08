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
