require 'spec_helper'

feature 'Defective state propagation' do
  include_context 'with executor api'

  scenario 'defective trial with max_trials=1 → task and job defective' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'max_trials' => 1})
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'defective'})

    expect(database[:tasks][id: task_id][:state]).to eq 'defective'
    expect(database[:jobs][id: job_id][:state]).to eq 'defective'
  end

  scenario 'defective task makes job defective even when another task passed' do
    job_id    = seed_job
    task1_id  = seed_task(job_id, spec: {'max_trials' => 1})
    task2_id  = seed_task(job_id)
    trial1    = seed_trial(task1_id)
    trial2    = seed_trial(task2_id)

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'defective'})
    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'passed'})

    expect(database[:tasks][id: task1_id][:state]).to eq 'defective'
    expect(database[:tasks][id: task2_id][:state]).to eq 'passed'
    expect(database[:jobs][id: job_id][:state]).to eq 'defective'
  end

  scenario 'defective trial retries by default (max_trials=2)' do
    job_id   = seed_job
    task_id  = seed_task(job_id)  # default max_trials: 2
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'defective'})

    # defective is not-passed and not-aborted → should trigger retry
    expect(database[:trials].where(task_id: task_id).count).to eq 2
    expect(database[:tasks][id: task_id][:state]).to eq 'pending'
  end

  scenario 'task with one passing trial is passed even alongside a defective trial' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'eager_trials' => 2, 'max_trials' => 3})
    trial1   = seed_trial(task_id)
    trial2   = seed_trial(task_id, state: 'pending')

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'defective'})
    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'passed'})

    # any-pass: task passed because at least one trial passed
    expect(database[:tasks][id: task_id][:state]).to eq 'passed'
  end
end
