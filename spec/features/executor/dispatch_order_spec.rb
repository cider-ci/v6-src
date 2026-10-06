require 'spec_helper'

# Dispatch order (legacy parity): the highest job priority first, within the
# same priority the oldest job first. Previously the dispatch query had no
# ORDER BY and PostgreSQL handed out pending trials in arbitrary order.
feature 'Dispatch order' do
  include_context 'with executor api'

  def seed_job_with_trial(key:, priority:, created_at:)
    job_id = database[:jobs].insert(project_id: project_id, commit_id: commit_id, key: key, name: key,
                                    state: 'pending', priority: priority, created_at: created_at)
    task_id = seed_task(job_id)
    seed_trial(task_id, state: 'pending')
    job_id
  end

  def next_dispatched_job_key
    code, body = executor_api(:post, '/executor/sync', { available_load: 1.0 })
    expect(code).to eq 200
    trial = body['trials_to_execute'].first
    return nil unless trial
    database[:jobs].where(id: trial['job_id']).get(:key)
  end

  scenario 'higher priority first, then the oldest job' do
    t0 = Time.now - 60
    seed_job_with_trial(key: 'old-normal',   priority: 0,   created_at: t0)
    seed_job_with_trial(key: 'newer-normal', priority: 0,   created_at: t0 + 10)
    seed_job_with_trial(key: 'newest-high',  priority: 10,  created_at: t0 + 20)
    seed_job_with_trial(key: 'newest-low',   priority: -5,  created_at: t0 + 30)

    expect(next_dispatched_job_key).to eq 'newest-high'
    expect(next_dispatched_job_key).to eq 'old-normal'
    expect(next_dispatched_job_key).to eq 'newer-normal'
    expect(next_dispatched_job_key).to eq 'newest-low'
    expect(next_dispatched_job_key).to be_nil
  end
end
