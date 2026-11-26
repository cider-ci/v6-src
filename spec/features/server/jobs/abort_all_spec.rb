require 'spec_helper'
require 'securerandom'
require 'timeout'

# "Abort all" on the commit's jobs page (legacy parity): every unfinished
# job of the commit is aborted, pending trials at once, running ones via the
# executor; finished jobs are untouched, and the job pages say who did it.
feature 'Abort all jobs of a commit' do

  let(:project_id)  { "abort-all-#{SecureRandom.hex(4)}" }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../../../fixtures/trigger-filter-repo.git').realdirpath }
  let(:commit_id)   { database[:branches].where(repository_id: project_id, name: 'master').get(:current_commit_id) }

  # the jobs page reads the commit's configuration from git: a real repository
  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: project_id, name: 'Abort All', git_url: "file://#{fixture_dir}",
                                   branch_trigger_include_match: '^__none__$')
    reload_server_repository_state
    sleep 1
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id, name: 'master').count == 1 }
  end

  def seed(key, job_state, trial_state)
    job  = database[:jobs].insert(project_id: project_id, commit_id: commit_id, key: key, name: key, state: job_state)
    task = database.fetch("INSERT INTO tasks (job_id, name, state, traits, load, spec)
                           VALUES (?, 'main', ?, '{}', 1.0, '{}'::jsonb) RETURNING id", job, job_state).first[:id]
    trial = database[:trials].insert(task_id: task, state: trial_state,
                                     dispatched_at: (trial_state == 'executing' ? Time.now.utc : nil))
    { job: job, trial: trial }
  end

  scenario 'aborts the pending and running jobs, leaves the finished one, says who' do
    pending   = seed('pending-job',   'pending',   'pending')
    executing = seed('executing-job', 'executing', 'executing')
    passed    = seed('passed-job',    'passed',    'passed')

    visit "/projects/#{project_id}/commits/#{commit_id}/jobs?state=executing"
    expect(page).to have_button 'Abort all (2)'
    accept_confirm { click_on 'Abort all (2)' }
    expect(page).not_to have_button(/Abort all/, wait: 10)

    expect(database[:jobs].where(id: pending[:job]).get(:state)).to eq 'aborting'
    expect(database[:trials].where(id: pending[:trial]).get(:state)).to eq 'aborted'
    expect(database[:jobs].where(id: executing[:job]).get(:state)).to eq 'aborting'
    expect(database[:trials].where(id: executing[:trial]).get(:state)).to eq 'aborting'
    expect(database[:jobs].where(id: passed[:job]).get(:state)).to eq 'passed'
    expect(database[:jobs].where(id: pending[:job]).get(:abort_reason)).to eq "aborted by #{@admin.login} with all jobs of the commit"

    visit "/projects/#{project_id}/commits/#{commit_id}/jobs/#{executing[:job]}"
    expect(page).to have_css '.abort-reason', text: "aborted by #{@admin.login} with all jobs of the commit"
  end

  scenario 'no button when nothing is unfinished' do
    seed('passed-job', 'passed', 'passed')
    visit "/projects/#{project_id}/commits/#{commit_id}/jobs"
    expect(page).to have_content 'Recorded Jobs'
    expect(page).not_to have_button(/Abort all/)
  end
end
