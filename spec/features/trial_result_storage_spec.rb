require 'spec_helper'
require 'json'

feature 'Trial result storage' do
  include_context 'with executor api'

  scenario 'scripts_results from executor PATCH are stored under trials.result' do
    job_id   = seed_job
    task_id  = seed_task(job_id)
    trial_id = seed_trial(task_id)

    scripts_results = {
      'main'  => {'state' => 'passed', 'exit_status' => 0},
      'setup' => {'state' => 'passed', 'exit_status' => 0}
    }
    executor_api(:patch, "/executor/trials/#{trial_id}", {
      state:           'passed',
      scripts_results: scripts_results
    })

    stored = JSON.parse(database[:trials][id: trial_id][:result].to_s)
    expect(stored['scripts']['main']['state']).to eq 'passed'
    expect(stored['scripts']['setup']['exit_status']).to eq 0
  end

  scenario 'error message from executor is stored on the trial' do
    job_id   = seed_job
    task_id  = seed_task(job_id)
    trial_id = seed_trial(task_id)

    executor_api(:patch, "/executor/trials/#{trial_id}", {
      state: 'defective',
      error: 'Git clone failed: repository not found'
    })

    trial = database[:trials][id: trial_id]
    expect(trial[:state]).to eq 'defective'
    expect(trial[:error]).to eq 'Git clone failed: repository not found'
  end

  scenario 'finished_at is set when trial reaches a terminal state' do
    job_id   = seed_job
    task_id  = seed_task(job_id)
    trial_id = seed_trial(task_id)

    expect(database[:trials][id: trial_id][:finished_at]).to be_nil

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'passed'})

    expect(database[:trials][id: trial_id][:finished_at]).not_to be_nil
  end

  scenario 'started_at is set when trial transitions to executing' do
    job_id   = seed_job
    task_id  = seed_task(job_id)
    trial_id = seed_trial(task_id)

    expect(database[:trials][id: trial_id][:started_at]).to be_nil

    executor_api(:patch, "/executor/trials/#{trial_id}", {state: 'executing'})

    expect(database[:trials][id: trial_id][:started_at]).not_to be_nil
  end
end
