require 'spec_helper'

feature 'Aggregate State: default (any-pass with retry)' do
  include_context 'with executor api'

  scenario 'failed trial triggers a retry up to max_trials' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'max_trials' => 3})
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'failed'})

    # task stays pending while a fresh trial is queued
    expect(database[:tasks][id: task_id][:state]).to eq 'pending'
    expect(database[:trials].where(task_id: task_id).count).to eq 2

    # second trial passes → task and job pass
    trial2 = database[:trials].where(task_id: task_id, state: 'pending').first
    executor_api(:patch, "/executor/trials/#{trial2[:id]}", {state: 'passed'})

    expect(database[:tasks][id: task_id][:state]).to eq 'passed'
    expect(database[:jobs][id: job_id][:state]).to eq 'passed'
    expect(database[:trials].where(task_id: task_id).count).to eq 2
  end

  scenario 'no retry once max_trials is exhausted' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'max_trials' => 1})
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'failed'})

    expect(database[:tasks][id: task_id][:state]).to eq 'failed'
    expect(database[:trials].where(task_id: task_id).count).to eq 1
  end

  scenario 'any-pass: first passing trial wins even if other trials are pending' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'eager_trials' => 2, 'max_trials' => 4})
    trial_id = seed_trial(task_id)

    # A second eager trial is seeded alongside the first
    seed_trial(task_id, state: 'pending')

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'passed'})

    expect(database[:tasks][id: task_id][:state]).to eq 'passed'
  end

  scenario 'eager_trials=2 creates two new trials on first failure' do
    job_id   = seed_job
    task_id  = seed_task(job_id, spec: {'eager_trials' => 2, 'max_trials' => 4})
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'failed'})

    # 1 failed + 2 new eager trials
    expect(database[:trials].where(task_id: task_id).count).to eq 3
    expect(database[:tasks][id: task_id][:state]).to eq 'pending'
  end
end
