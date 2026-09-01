require 'spec_helper'

feature 'Aggregate State: satisfy-last' do
  include_context 'with executor api'

  scenario 'trial passed → task passed immediately, no retry' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'aggregate_state' => 'satisfy-last'})
    trial_id = seed_trial(task_id)

    code, = executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'passed'})

    expect(code).to eq 200
    expect(database[:tasks][id: task_id][:state]).to eq 'passed'
    expect(database[:trials].where(task_id: task_id).count).to eq 1
  end

  scenario 'trial failed → task failed, no retry even when max_trials > 1' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'aggregate_state' => 'satisfy-last', 'max_trials' => 5})
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'failed'})

    expect(database[:tasks][id: task_id][:state]).to eq 'failed'
    expect(database[:trials].where(task_id: task_id).count).to eq 1
  end

  scenario 'task and job state propagate together when trial passes' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'aggregate_state' => 'satisfy-last'})
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'passed'})

    expect(database[:jobs][id: job_id][:state]).to eq 'passed'
  end
end
