require 'spec_helper'
require 'net/http'
require 'securerandom'

feature 'Job abort' do

  let(:project_id) { 'abort-test-project' }
  let(:commit_id)  { 'a' * 40 }
  let(:tree_id)    { 'b' * 40 }

  before :each do
    @admin = FactoryBot.create(:admin)

    database[:repositories].insert(id: project_id, name: 'Abort Test', git_url: 'local')
    database[:commits].insert(
      id: commit_id, tree_id: tree_id, subject: 'Test abort',
      author_name: 'A', committer_name: 'A', committer_date: Time.now.utc
    )
  end

  def abort_job_http(job_id)
    uri = URI("#{http_base_url}/projects/#{project_id}/commits/#{commit_id}/jobs/#{job_id}/abort")
    req = Net::HTTP::Post.new(uri)
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req['Cookie']        = "cider-ci-session=#{@admin.session_token}"
    Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
  end

  def seed_job_with_task(job_state: 'executing', trial_state: 'executing')
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    database[:jobs].insert(
      id: job_id, project_id: project_id, commit_id: commit_id,
      key: "job-#{SecureRandom.hex(4)}", name: 'Test Job', state: job_state
    )
    task_state = (job_state == 'pending') ? 'pending' : 'executing'
    database.fetch(
      "INSERT INTO tasks (id, job_id, name, state, traits, load, spec)
       VALUES (?, ?, 'main', ?, '{}', 1.0, '{}'::jsonb)",
      task_id, job_id, task_state
    ).first
    database[:trials].insert(id: trial_id, task_id: task_id, state: trial_state)

    {job_id: job_id, task_id: task_id, trial_id: trial_id}
  end

  def job_url(job_id)
    "/projects/#{project_id}/commits/#{commit_id}/jobs/#{job_id}"
  end

  # ── HTTP endpoint tests (Plan A style) ───────────────────────────────────

  scenario 'pending job: pending trials become aborted; job → aborting' do
    ids = seed_job_with_task(job_state: 'pending', trial_state: 'pending')

    res = abort_job_http(ids[:job_id])
    expect(res.code.to_i).to eq 200

    expect(database[:trials][id: ids[:trial_id]][:state]).to eq 'aborted'
    expect(database[:tasks][id: ids[:task_id]][:state]).to   eq 'aborted'
    expect(database[:jobs][id: ids[:job_id]][:state]).to     eq 'aborting'
  end

  scenario 'executing job: active trials become aborting; job → aborting' do
    ids = seed_job_with_task(job_state: 'executing', trial_state: 'executing')

    res = abort_job_http(ids[:job_id])
    expect(res.code.to_i).to eq 200

    expect(database[:trials][id: ids[:trial_id]][:state]).to eq 'aborting'
    expect(database[:tasks][id: ids[:task_id]][:state]).to   eq 'aborting'
    expect(database[:jobs][id: ids[:job_id]][:state]).to     eq 'aborting'
  end

  scenario 'passed job: abort returns 200 but state is unchanged' do
    ids = seed_job_with_task(job_state: 'passed', trial_state: 'passed')

    res = abort_job_http(ids[:job_id])
    expect(res.code.to_i).to eq 200

    expect(database[:jobs][id: ids[:job_id]][:state]).to eq 'passed'
  end

  scenario 'non-existent job returns 404' do
    res = abort_job_http(SecureRandom.uuid)
    expect(res.code.to_i).to eq 404
  end

  # ── UI rendering tests (Capybara) ────────────────────────────────────────

  scenario 'Abort button appears for executing job, not for passed job' do
    set_session_cookie @admin
    exec_ids   = seed_job_with_task(job_state: 'executing', trial_state: 'executing')
    passed_ids = seed_job_with_task(job_state: 'passed',    trial_state: 'passed')

    visit job_url(exec_ids[:job_id])
    expect(page).to have_css '.badge', text: 'executing'
    expect(page).to have_button 'Abort'
    expect(page).not_to have_button 'Retry'

    visit job_url(passed_ids[:job_id])
    expect(page).to have_css '.badge', text: 'passed'
    expect(page).not_to have_button 'Abort'
    expect(page).not_to have_button 'Retry'
  end

  scenario 'Retry button appears for failed job, not for executing job' do
    set_session_cookie @admin
    failed_ids = seed_job_with_task(job_state: 'failed', trial_state: 'failed')
    exec_ids   = seed_job_with_task(job_state: 'executing', trial_state: 'executing')

    visit job_url(failed_ids[:job_id])
    expect(page).to have_css '.badge', text: 'failed'
    expect(page).to have_button 'Retry'
    expect(page).not_to have_button 'Abort'

    visit job_url(exec_ids[:job_id])
    expect(page).to have_css '.badge', text: 'executing'
    expect(page).to have_button 'Abort'
    expect(page).not_to have_button 'Retry'
  end
end
