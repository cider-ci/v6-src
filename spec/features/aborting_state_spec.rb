require 'spec_helper'

feature 'Aborting and aborted state propagation' do
  include_context 'with executor api'

  scenario 'all trials aborted → task aborted → job aborted' do
    job_id   = seed_job(state: 'executing')
    task_id  = seed_task(job_id, state: 'executing')
    trial_id = seed_trial(task_id, state: 'aborting')

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'aborted'})

    expect(database[:tasks][id: task_id][:state]).to eq 'aborted'
    expect(database[:jobs][id: job_id][:state]).to eq 'aborted'
  end

  scenario 'aborted trial does not trigger a retry (aborted is final)' do
    job_id   = seed_job(state: 'executing')
    task_id  = seed_task(job_id, state: 'executing', spec: {'max_trials' => 5})
    trial_id = seed_trial(task_id, state: 'aborting')

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'aborted'})

    # no new trial — aborted is treated as final, not a failure to retry
    expect(database[:trials].where(task_id: task_id).count).to eq 1
    expect(database[:tasks][id: task_id][:state]).to eq 'aborted'
  end

  scenario 'job with mixed aborted and passed tasks is failed (not aborted)' do
    job_id    = seed_job(state: 'executing')
    task1_id  = seed_task(job_id, state: 'executing')
    task2_id  = seed_task(job_id, state: 'executing')
    trial1    = seed_trial(task1_id, state: 'aborting')
    trial2    = seed_trial(task2_id, state: 'dispatching')

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'aborted'})
    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'passed'})

    expect(database[:tasks][id: task1_id][:state]).to eq 'aborted'
    expect(database[:tasks][id: task2_id][:state]).to eq 'passed'
    # job-state-from-tasks: aborted + passed → failed (not all aborted)
    expect(database[:jobs][id: job_id][:state]).to eq 'failed'
  end
end
