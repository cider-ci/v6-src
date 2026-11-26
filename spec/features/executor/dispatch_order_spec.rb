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

  # The work of branch heads comes before the work of commits behind them,
  # whatever the job priorities: a push moves the head, and what is still
  # running or waiting on the previous commits yields to the new head.
  scenario 'the distance to the nearest branch head takes precedence over the job priority' do
    head, mid, orphan = 'h' * 40, 'm' * 40, 'o' * 40
    database[:commits].insert(id: head,   tree_id: 'h' * 40, subject: 'head',   author_name: 'A', committer_name: 'A', committer_date: Time.now.utc, depth: 2)
    database[:commits].insert(id: mid,    tree_id: 'm' * 40, subject: 'mid',    author_name: 'A', committer_name: 'A', committer_date: Time.now.utc, depth: 1)
    database[:commits].insert(id: orphan, tree_id: 'o' * 40, subject: 'orphan', author_name: 'A', committer_name: 'A', committer_date: Time.now.utc, depth: 7)
    master = database[:branches].insert(repository_id: project_id, name: 'master', current_commit_id: head)
    database[:branches_commits].insert(branch_id: master, commit_id: head)
    database[:branches_commits].insert(branch_id: master, commit_id: mid)

    t0 = Time.now - 60
    seed = lambda do |key, commit, priority, created_at|
      job_id = database[:jobs].insert(project_id: project_id, commit_id: commit, key: key, name: key,
                                      state: 'pending', priority: priority, created_at: created_at)
      seed_trial(seed_task(job_id), state: 'pending')
    end
    seed.call('orphan-top-priority', orphan, 999, t0)
    seed.call('mid-high',            mid,    10,  t0 + 10)
    seed.call('head-low',            head,   -5,  t0 + 20)
    seed.call('head-normal',         head,   0,   t0 + 30)

    expect(next_dispatched_job_key).to eq 'head-normal'          # head, priority 0 beats -5
    expect(next_dispatched_job_key).to eq 'head-low'
    expect(next_dispatched_job_key).to eq 'mid-high'             # one behind the head
    expect(next_dispatched_job_key).to eq 'orphan-top-priority'  # no head reaches it: last
    expect(next_dispatched_job_key).to be_nil
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
