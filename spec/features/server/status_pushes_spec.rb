require 'spec_helper'
require 'securerandom'

# GitHub commit statuses (legacy parity): every job state is posted to
# /repos/:owner/:repo/statuses/:sha with a link back to the job page. The
# token is configured per project.
feature 'GitHub status pushes' do
  include_context 'with fake github'

  let(:project_id) { 'status-test' }
  let(:commit_id)  { 'a' * 40 }
  let(:token)      { "token-#{SecureRandom.hex(8)}" }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: project_id, name: 'Status Test',
                                   git_url: 'https://github.com/cider-ci/status-test.git')
    database[:commits].insert(id: commit_id, tree_id: 'b' * 40, subject: 'Status',
                              author_name: 'A', committer_name: 'A', committer_date: Time.now.utc)
  end

  def configure_pushes!(**attrs)
    database[:repositories].where(id: project_id)
      .update({ remote_api_endpoint: @fake_github.url, remote_api_token: token }.merge(attrs))
  end

  def create_job!(state: 'pending')
    @job_id = SecureRandom.uuid
    database[:jobs].insert(id: @job_id, project_id: project_id, commit_id: commit_id,
                           key: 'status-job', name: 'Status Job', state: state)
  end

  def set_job_state!(state)
    database[:jobs].where(id: @job_id).update(state: state, updated_at: Sequel.function(:now))
  end

  def job_path
    "/projects/#{project_id}/commits/#{commit_id}/jobs/#{@job_id}"
  end

  scenario 'every job state is posted with context, description and a link to the job' do
    configure_pushes!
    create_job!

    pending = wait_for_github_request('pending')
    expect(pending.method).to eq 'POST'
    expect(pending.path).to eq "/repos/cider-ci/status-test/statuses/#{commit_id}"
    expect(pending.headers['authorization']).to eq "Bearer #{token}"
    expect(pending.body).to eq('state' => 'pending',
                               'context' => 'Cider-CI@localhost - Status Job',
                               'description' => 'pending - Status Job',
                               'target_url' => "http://localhost:3838#{job_path}")

    set_job_state!('passed')
    expect(wait_for_github_request('success').body['description']).to eq 'passed - Status Job'
    set_job_state!('failed')
    wait_for_github_request('failure')
    set_job_state!('aborted')
    wait_for_github_request('error')

    visit job_path
    expect(page).to have_css '.status-push', text: 'GitHub status: error'
    visit "/projects/#{project_id}"
    expect(page).to have_css '.status-pushes', text: 'pushed to cider-ci/status-test'
  end

  scenario 'a failed post is shown and retried' do
    configure_pushes!
    @fake_github.respond_next(500, message: 'Something broke')
    create_job!(state: 'passed')

    wait_until(15) { github_requests.size >= 1 }
    visit job_path
    expect(page).to have_css '.status-push-error', text: 'GitHub responded 500: Something broke'
    visit "/projects/#{project_id}"
    expect(page).to have_css '.status-pushes-last-error', text: 'Something broke'

    # retried after 10 s backoff
    wait_until(30) { github_requests.size >= 2 }
    expect(github_requests.last.body['state']).to eq 'success'
    visit job_path
    expect(page).to have_css '.status-push', text: 'GitHub status: success'
    expect(page).not_to have_css '.status-push-error'
  end

  scenario 'nothing is posted without a token or when disabled' do
    create_job!(state: 'passed')
    database[:repositories].where(id: project_id).update(remote_api_endpoint: @fake_github.url)
    visit "/projects/#{project_id}"
    expect(page).to have_css '.status-pushes', text: 'no token configured'

    configure_pushes!(send_status_notifications: false)
    visit "/projects/#{project_id}"
    expect(page).to have_css '.status-pushes', text: 'disabled'
    sleep 5
    expect(github_requests).to be_empty
  end

  scenario 'push now posts the recent jobs again' do
    configure_pushes!
    create_job!(state: 'passed')
    wait_for_github_request('success')

    visit "/projects/#{project_id}"
    click_on 'Push statuses now'
    wait_until(15) { github_requests.count { |r| r.body['state'] == 'success' } >= 2 }
  end

  scenario 'the settings are edited in the project form; the token is write-only' do
    visit "/projects/#{project_id}/edit"
    expect(page).to have_field 'send_status_notifications', checked: true
    fill_in 'remote_api_token', with: token
    fill_in 'remote_api_endpoint', with: @fake_github.url
    click_on 'Save'
    expect(page).to have_css '.status-pushes', text: 'pushed to cider-ci/status-test'
    expect(database[:repositories].where(id: project_id).first[:remote_api_token]).to eq token

    json = page.evaluate_async_script(<<~JS)
      const done = arguments[0];
      fetch('/projects/#{project_id}', {headers: {accept: 'application/json'}})
        .then(r => r.text()).then(done);
    JS
    expect(json).to include '"remote_api_token_set":true'
    expect(json).not_to include token

    create_job!(state: 'passed')
    wait_for_github_request('success')

    visit "/projects/#{project_id}/edit"
    expect(page).to have_field 'remote_api_token', with: ''
    check 'remote_api_token_clear'
    fill_in 'remote_api_namespace', with: 'other-owner'
    click_on 'Save'
    expect(page).to have_css '.status-pushes', text: 'no token configured'
    repo = database[:repositories].where(id: project_id).first
    expect(repo[:remote_api_token]).to be_nil
    expect(repo[:remote_api_namespace]).to eq 'other-owner'
  end
end
