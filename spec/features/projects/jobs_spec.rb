require 'spec_helper'
require 'net/http'
require 'json'
require 'digest'
require 'securerandom'

feature 'Jobs' do

  JOBS_PROJECT_ID  = 'cider-ci-demo-project'
  JOBS_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      JOBS_PROJECT_ID,
      name:    'Demo Project',
      git_url: 'local'
    )
  end

  def executor_api(method, path, body, token)
    uri = URI("#{http_base_url}#{path}")
    req = case method
          when :post  then Net::HTTP::Post.new(uri)
          when :patch then Net::HTTP::Patch.new(uri)
          end
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req['Authorization'] = "Bearer #{token}"
    req.body = body.to_json if body
    Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
  end

  def setup_executor_token
    token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       'jobs-spec-executor',
      token_hash: Digest::SHA256.hexdigest(token),
      token_part: token[0, 8],
      enabled:    true
    )
    token
  end

  # Inserts a complete job+task+trial hierarchy directly into the DB.
  # Returns { job_id:, task_id:, trial_id:, commit_id: }.
  def insert_job_hierarchy(state: 'failed', error: nil, with_timing: false)
    commit_id = 'd' * 40
    job_id    = SecureRandom.uuid
    task_id   = SecureRandom.uuid
    trial_id  = SecureRandom.uuid

    database[:commits].insert(
      id: commit_id, tree_id: 'e' * 40,
      author_name: 'A', committer_name: 'A', subject: 'test'
    )
    database[:jobs].insert(
      id: job_id, project_id: JOBS_PROJECT_ID,
      commit_id: commit_id, key: 'test-job', name: 'Test Job', state: state
    )
    database[:tasks].insert(id: task_id, job_id: job_id, name: 'main', state: state)

    trial_attrs = { id: trial_id, task_id: task_id, state: state }
    trial_attrs[:error]       = error           if error
    trial_attrs[:started_at]  = Time.now.utc - 5 if with_timing
    trial_attrs[:finished_at] = Time.now.utc      if with_timing
    database[:trials].insert(trial_attrs)

    { job_id: job_id, task_id: task_id, trial_id: trial_id, commit_id: commit_id }
  end

  scenario 'lists available jobs from cider-ci.yml' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    expect(page).to have_content 'Available Jobs'
    expect(page).to have_content 'introduction-demo'
  end

  scenario 'records a job when Run is clicked' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    expect(page).to have_content 'introduction-demo'

    first(:button, 'Run').click
    expect(page).to have_content 'Recorded Jobs'
    expect(page).to have_css '.badge', text: 'pending'
  end

  scenario 'shows no recorded jobs initially' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    expect(page).not_to have_content 'Recorded Jobs'
  end

  scenario 'job detail page shows tasks after triggering a job' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    find('a code', text: 'introduction-demo').click

    expect(page).to have_content 'Tasks'
    expect(page).to have_css '.badge', text: 'pending'
    expect(page).to have_content 'main'
  end

  scenario 'job detail shows error message from a failed trial' do
    token = setup_executor_token
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click

    resp  = executor_api(:post, '/executor/sync', { available_load: 1.0 }, token)
    trial = JSON.parse(resp.body)['trials_to_execute'].first
    executor_api(:patch, "/executor/trials/#{trial['id']}", { state: 'executing' }, token)
    executor_api(:patch, "/executor/trials/#{trial['id']}",
                 { state: 'failed', error: 'script exited with code 1' }, token)

    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs/#{trial['job_id']}"
    expect(page).to have_content 'script exited with code 1'
    expect(page).to have_css '.badge', text: 'failed'
  end

  scenario 'job detail shows Log link for each trial' do
    h = insert_job_hierarchy
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{h[:commit_id]}/jobs/#{h[:job_id]}"
    expect(page).to have_link 'Log', href: "/trials/#{h[:trial_id]}/attachments/log"
  end

  scenario 'job detail shows trial duration when trial has timing data' do
    h = insert_job_hierarchy(with_timing: true)
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{h[:commit_id]}/jobs/#{h[:job_id]}"
    expect(page).to have_css '.text-muted.small', text: /\d+s/
  end

  scenario 'job detail shows Retry button for a failed job' do
    h = insert_job_hierarchy
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{h[:commit_id]}/jobs/#{h[:job_id]}"
    expect(page).to have_button 'Retry'
  end

  scenario 'job detail returns 404 for unknown job id' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs/#{SecureRandom.uuid}"
    expect(page).to have_content 'Request ERROR 404'
  end

  scenario 'job shows executing badge while trial is running' do
    token = setup_executor_token
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click

    resp  = executor_api(:post, '/executor/sync', { available_load: 1.0 }, token)
    trial = JSON.parse(resp.body)['trials_to_execute'].first
    executor_api(:patch, "/executor/trials/#{trial['id']}", { state: 'executing' }, token)

    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs/#{trial['job_id']}"
    expect(page).to have_css '.badge', text: 'executing'
  end

end
