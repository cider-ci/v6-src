require 'spec_helper'

feature 'Job state edge cases (job-state-from-tasks priority)' do
  include_context 'with executor api'

  # job-state-from-tasks priority when all tasks are terminal:
  #   all-passed → passed
  #   all-aborted → aborted
  #   any-defective → defective
  #   any-aborted (mixed) → failed
  #   else → failed

  scenario 'all tasks defective → job defective' do
    job_id   = seed_job(state: 'executing')
    task1_id = seed_task(job_id, state: 'executing', spec: {'max_trials' => 1})
    task2_id = seed_task(job_id, state: 'executing', spec: {'max_trials' => 1})
    trial1   = seed_trial(task1_id)
    trial2   = seed_trial(task2_id)

    executor_api(:patch, "/executor/trials/#{trial1}", {state: 'defective'})
    executor_api(:patch, "/executor/trials/#{trial2}", {state: 'defective'})

    expect(database[:tasks][id: task1_id][:state]).to eq 'defective'
    expect(database[:tasks][id: task2_id][:state]).to eq 'defective'
    expect(database[:jobs][id: job_id][:state]).to eq 'defective'
  end

  scenario 'defective task + aborted task → job defective (defective beats aborted)' do
    job_id      = seed_job(state: 'executing')
    defect_task = seed_task(job_id, state: 'executing', spec: {'max_trials' => 1})
    abort_task  = seed_task(job_id, state: 'executing')
    t_defect    = seed_trial(defect_task)
    t_abort     = seed_trial(abort_task, state: 'aborting')

    executor_api(:patch, "/executor/trials/#{t_defect}", {state: 'defective'})
    executor_api(:patch, "/executor/trials/#{t_abort}",  {state: 'aborted'})

    expect(database[:tasks][id: defect_task][:state]).to eq 'defective'
    expect(database[:tasks][id: abort_task][:state]).to  eq 'aborted'
    # defective takes priority over aborted
    expect(database[:jobs][id: job_id][:state]).to eq 'defective'
  end

  scenario 'failed task + aborted task → job failed (aborted-in-mix counts as failed)' do
    job_id     = seed_job(state: 'executing')
    fail_task  = seed_task(job_id, state: 'executing', spec: {'max_trials' => 1})
    abort_task = seed_task(job_id, state: 'executing')
    t_fail     = seed_trial(fail_task)
    t_abort    = seed_trial(abort_task, state: 'aborting')

    executor_api(:patch, "/executor/trials/#{t_fail}",  {state: 'failed'})
    executor_api(:patch, "/executor/trials/#{t_abort}", {state: 'aborted'})

    expect(database[:tasks][id: fail_task][:state]).to  eq 'failed'
    expect(database[:tasks][id: abort_task][:state]).to eq 'aborted'
    # mixed aborted + failed → failed (aborted-in-mix is not "all-aborted")
    expect(database[:jobs][id: job_id][:state]).to eq 'failed'
  end

  scenario 'defective task + failed task → job defective (defective beats plain failure)' do
    job_id      = seed_job(state: 'executing')
    defect_task = seed_task(job_id, state: 'executing', spec: {'max_trials' => 1})
    fail_task   = seed_task(job_id, state: 'executing', spec: {'max_trials' => 1})
    t_defect    = seed_trial(defect_task)
    t_fail      = seed_trial(fail_task)

    executor_api(:patch, "/executor/trials/#{t_defect}", {state: 'defective'})
    executor_api(:patch, "/executor/trials/#{t_fail}",   {state: 'failed'})

    expect(database[:tasks][id: defect_task][:state]).to eq 'defective'
    expect(database[:tasks][id: fail_task][:state]).to   eq 'failed'
    expect(database[:jobs][id: job_id][:state]).to eq 'defective'
  end
end
