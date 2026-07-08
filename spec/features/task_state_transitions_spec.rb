require 'spec_helper'

feature 'Task state transitions (trial priority logic)' do
  include_context 'with executor api'

  # task-state-from-trials priority:
  #   passed > executing/dispatching > aborting > pending > aborted > defective > failed

  scenario 'executing trial beats aborting — task stays executing' do
    job_id  = seed_job(state: 'executing')
    task_id = seed_task(job_id, state: 'executing')
    _a      = seed_trial(task_id, state: 'aborting')    # seeded as aborting
    trial2  = seed_trial(task_id, state: 'dispatching')

    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'executing'})

    # executing > aborting in priority — task must be executing, not aborting
    expect(database[:tasks][id: task_id][:state]).to eq 'executing'
  end

  scenario 'aborting trial beats pending — task is aborting' do
    job_id  = seed_job(state: 'executing')
    task_id = seed_task(job_id, state: 'executing')
    trial1  = seed_trial(task_id, state: 'dispatching')
    _p      = seed_trial(task_id, state: 'pending')

    # executor marks trial as aborting (e.g. received abort signal)
    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'aborting'})

    # aborting > pending — task should be aborting, not pending
    expect(database[:tasks][id: task_id][:state]).to eq 'aborting'
  end

  scenario 'satisfy-last: last trial result overrides an earlier passing trial' do
    job_id  = seed_job(state: 'executing')
    task_id = seed_task(job_id, state: 'executing',
                         spec: {'aggregate_state' => 'satisfy-last'})
    trial1  = seed_trial(task_id, state: 'dispatching')  # created first
    trial2  = seed_trial(task_id, state: 'dispatching')  # created second → last

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'passed'})
    # trial2 is still dispatching; it's the last-created trial.
    # task should not be "passed" yet — last trial hasn't reported.
    # (satisfy-last maps dispatching → executing for task state)
    expect(database[:tasks][id: task_id][:state]).to eq 'executing'

    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'failed'})
    # last trial failed → task failed, despite trial1 having passed
    expect(database[:tasks][id: task_id][:state]).to eq 'failed'
  end

  scenario 'satisfy-last: dispatching as last trial maps task state to executing' do
    job_id  = seed_job(state: 'executing')
    task_id = seed_task(job_id, state: 'executing',
                         spec: {'aggregate_state' => 'satisfy-last'})
    # trial1 created first, trial2 created second (= "last" in satisfy-last)
    trial1  = seed_trial(task_id, state: 'dispatching')
    _trial2 = seed_trial(task_id, state: 'dispatching')

    # patching trial1 triggers propagation; trial2 (last) is still dispatching
    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'passed'})

    # satisfy-last sees last trial as "dispatching" → reports task as "executing"
    expect(database[:tasks][id: task_id][:state]).to eq 'executing'
  end
end
