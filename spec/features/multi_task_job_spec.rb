require 'spec_helper'

feature 'Multi-task Job state aggregation' do
  include_context 'with executor api'

  scenario 'all tasks pass → job passes' do
    job_id   = seed_job
    task1_id = seed_task(job_id)
    task2_id = seed_task(job_id)
    trial1   = seed_trial(task1_id)
    trial2   = seed_trial(task2_id)

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'passed'})
    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'passed'})

    expect(database[:jobs][id: job_id][:state]).to eq 'passed'
  end

  scenario 'one task fails → job fails (even if other task passed)' do
    job_id   = seed_job
    task1_id = seed_task(job_id, spec: {'max_trials' => 1})
    task2_id = seed_task(job_id)
    trial1   = seed_trial(task1_id)
    trial2   = seed_trial(task2_id)

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'failed'})
    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'passed'})

    expect(database[:jobs][id: job_id][:state]).to eq 'failed'
  end

  scenario 'first task passing does not prematurely resolve job with pending tasks' do
    job_id   = seed_job
    task1_id = seed_task(job_id)
    task2_id = seed_task(job_id)
    trial1   = seed_trial(task1_id)
    _trial2  = seed_trial(task2_id)

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'passed'})

    # task2 still pending — job must not be resolved yet
    expect(database[:jobs][id: job_id][:state]).not_to eq 'passed'
  end

  scenario 'executing trial sets job to executing' do
    job_id   = seed_job
    task_id  = seed_task(job_id)
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'executing'})

    expect(database[:jobs][id: job_id][:state]).to eq 'executing'
    expect(database[:tasks][id: task_id][:state]).to eq 'executing'
  end
end
