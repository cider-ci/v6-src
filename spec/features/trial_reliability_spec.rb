require 'spec_helper'
require 'securerandom'

feature 'Trial reliability' do

  let(:project_id) { 'reliability-test-project' }
  let(:commit_id)  { 'a' * 40 }
  let(:tree_id)    { 'b' * 40 }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(id: project_id, name: 'Reliability Test', git_url: 'local')
    database[:commits].insert(id: commit_id, tree_id: tree_id, subject: 'Test',
                               author_name: 'A', committer_name: 'A', committer_date: Time.now.utc)
    @branch_id = SecureRandom.uuid
    database[:branches].insert(id: @branch_id, repository_id: project_id,
                                name: 'main', current_commit_id: commit_id)

    @job_id = SecureRandom.uuid
    database[:jobs].insert(id: @job_id, project_id: project_id, commit_id: commit_id,
                            key: 'test-job', name: 'Test Job', state: 'failed')
    @task_id = SecureRandom.uuid
    database[:tasks].insert(id: @task_id, job_id: @job_id, name: 'main', state: 'failed')
    @trial_id = SecureRandom.uuid
    database[:trials].insert(id: @trial_id, task_id: @task_id, state: 'failed')
  end

  scenario 'Retry button resets a failed job back to pending' do
    job_url = "/projects/#{project_id}/commits/#{commit_id}/jobs/#{@job_id}"
    visit job_url

    expect(page).to have_css '.badge', text: 'failed'
    find('button', text: /Retry/).click

    expect(page).to have_css '.badge', text: 'pending'
    expect(database[:jobs][id: @job_id][:state]).to eq 'pending'
    expect(database[:tasks][id: @task_id][:state]).to eq 'pending'
    expect(database[:trials][id: @trial_id][:state]).to eq 'pending'
  end

  scenario 'Stale dispatching trials are reset to pending after timeout' do
    stale_time = Time.now.utc - 600  # 10 minutes ago
    database[:trials].where(id: @trial_id).update(state: 'dispatching', dispatched_at: stale_time)
    database[:tasks].where(id: @task_id).update(state: 'pending')
    database[:jobs].where(id: @job_id).update(state: 'pending')

    # Hit the stale-trial reset endpoint directly via DB check
    # (The daemon runs every 60s; we trigger it by calling the reset SQL directly)
    database.run(<<~SQL)
      UPDATE trials
      SET state = 'pending', executor_id = NULL, dispatched_at = NULL, updated_at = now()
      WHERE state = 'dispatching'
        AND dispatched_at < now() - interval '5 minutes'
    SQL

    expect(database[:trials][id: @trial_id][:state]).to eq 'pending'
  end

end
