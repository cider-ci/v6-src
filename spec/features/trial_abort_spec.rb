require 'spec_helper'

feature 'Trial Abort via Sync' do
  include_context 'with executor api'

  scenario 'aborting trial appears in trials_to_abort on sync' do
    job_id   = seed_job(state: 'executing')
    task_id  = seed_task(job_id, state: 'executing')
    trial_id = seed_trial(task_id, state: 'aborting')

    # Link the trial to our executor
    executor = database[:executors].where(token_hash: Digest::SHA256.hexdigest(@executor_token)).first
    database[:trials].where(id: trial_id).update(executor_id: executor[:id])

    _code, body = executor_api(:post, '/executor/sync', {available_load: 1.0})

    expect(body['trials_to_abort']).to include(trial_id.to_s)
  end

  scenario 'patching trial to aborted propagates aborted state to task and job' do
    job_id   = seed_job(state: 'executing')
    task_id  = seed_task(job_id, state: 'aborting')
    trial_id = seed_trial(task_id, state: 'aborting')

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'aborted'})

    expect(database[:tasks][id: task_id][:state]).to eq 'aborted'
    expect(database[:jobs][id: job_id][:state]).to eq 'aborted'
  end
end
