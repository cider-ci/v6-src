require 'spec_helper'
require 'net/http'
require 'json'
require 'digest'
require 'securerandom'

feature 'Trial Attachments' do

  ATTACH_PROJECT_ID  = 'cider-ci-demo-project'
  ATTACH_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  def executor_call(method, path, body, token)
    uri = URI("#{http_base_url}#{path}")
    req = case method
          when :post  then Net::HTTP::Post.new(uri)
          when :patch then Net::HTTP::Patch.new(uri)
          when :put   then Net::HTTP::Put.new(uri)
          end
    req['Authorization'] = "Bearer #{token}"
    req['Accept']        = 'application/json'
    if body.is_a?(String)
      req['Content-Type'] = 'text/plain'
      req.body = body
    elsif body
      req['Content-Type'] = 'application/json'
      req.body = body.to_json
    end
    Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
  end

  def user_get(path, session_token)
    uri = URI("#{http_base_url}#{path}")
    req = Net::HTTP::Get.new(uri)
    req['Cookie'] = "cider-ci-session=#{session_token}"
    Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      ATTACH_PROJECT_ID,
      name:    'Demo Project',
      git_url: 'local'
    )

    @token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       'attach-test-executor',
      token_hash: Digest::SHA256.hexdigest(@token),
      token_part: @token[0, 8],
      enabled:    true
    )
  end

  scenario 'executor uploads attachment; logged-in user downloads it' do
    visit "/projects/#{ATTACH_PROJECT_ID}/commits/#{ATTACH_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click

    resp     = executor_call(:post, '/executor/sync', { available_load: 1.0 }, @token)
    trial    = JSON.parse(resp.body)['trials_to_execute'].first
    trial_id = trial['id']

    content = "hello from the executor\nline two\n"
    executor_call(:put, "/executor/trials/#{trial_id}/attachments/log", content, @token)

    resp = user_get("/trials/#{trial_id}/attachments/log", @admin.session_token)
    expect(resp.code.to_i).to eq(200)
    expect(resp['content-type']).to include('text/plain')
    expect(resp.body).to eq(content)
  end

  scenario 'overwriting an attachment replaces content' do
    visit "/projects/#{ATTACH_PROJECT_ID}/commits/#{ATTACH_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click

    resp     = executor_call(:post, '/executor/sync', { available_load: 1.0 }, @token)
    trial_id = JSON.parse(resp.body)['trials_to_execute'].first['id']

    executor_call(:put, "/executor/trials/#{trial_id}/attachments/log", 'first version', @token)
    executor_call(:put, "/executor/trials/#{trial_id}/attachments/log", 'second version', @token)

    resp = user_get("/trials/#{trial_id}/attachments/log", @admin.session_token)
    expect(resp.body).to eq('second version')
  end

  scenario 'returns 404 for attachment that does not exist' do
    resp = user_get("/trials/#{SecureRandom.uuid}/attachments/missing", @admin.session_token)
    expect(resp.code.to_i).to eq(404)
  end

  scenario 'unauthenticated request returns 403' do
    resp = user_get("/trials/#{SecureRandom.uuid}/attachments/log", SecureRandom.uuid)
    expect(resp.code.to_i).to eq(403)
  end

  scenario 'executor uploads tree attachment; logged-in user downloads it' do
    visit "/projects/#{ATTACH_PROJECT_ID}/commits/#{ATTACH_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click

    resp     = executor_call(:post, '/executor/sync', { available_load: 1.0 }, @token)
    trial    = JSON.parse(resp.body)['trials_to_execute'].first
    trial_id = trial['id']

    content = "shared tree output\n"
    resp = executor_call(:put, "/executor/trials/#{trial_id}/tree-attachments/report.txt",
                         content, @token)
    expect(resp.code.to_i).to eq(201)

    # Resolve tree_id for this commit
    tree_id = database[:commits].where(id: ATTACH_HEAD_COMMIT).get(:tree_id)

    resp = user_get("/tree-attachments/#{tree_id}/report.txt", @admin.session_token)
    expect(resp.code.to_i).to eq(200)
    expect(resp['content-type']).to include('text/plain')
    expect(resp.body).to eq(content)
  end

  scenario 'tree attachment is deduplicated by tree_id across trials' do
    visit "/projects/#{ATTACH_PROJECT_ID}/commits/#{ATTACH_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click

    resp     = executor_call(:post, '/executor/sync', { available_load: 1.0 }, @token)
    trial_id = JSON.parse(resp.body)['trials_to_execute'].first['id']

    executor_call(:put, "/executor/trials/#{trial_id}/tree-attachments/shared.txt",
                  'first version', @token)
    executor_call(:put, "/executor/trials/#{trial_id}/tree-attachments/shared.txt",
                  'second version', @token)

    tree_id = database[:commits].where(id: ATTACH_HEAD_COMMIT).get(:tree_id)
    resp = user_get("/tree-attachments/#{tree_id}/shared.txt", @admin.session_token)
    expect(resp.body).to eq('second version')
  end

  scenario 'non-script attachment appears in Attachments section on trial detail page' do
    project_id = ATTACH_PROJECT_ID  # already inserted in before :each
    commit_id  = 'c' * 40
    job_id     = SecureRandom.uuid
    task_id    = SecureRandom.uuid
    trial_id   = SecureRandom.uuid

    database[:commits].insert(
      id: commit_id, tree_id: 'd' * 40, subject: 'UI test',
      author_name: 'T', committer_name: 'T', committer_date: Time.now.utc
    )
    database[:jobs].insert(
      id: job_id, project_id: project_id, commit_id: commit_id,
      key: 'attach-job', name: 'Attach Job', state: 'passed'
    )
    database.fetch(
      "INSERT INTO tasks (id, job_id, name, state, traits, load, spec)
       VALUES (?, ?, 'main', 'passed', '{}', 1.0, '{}'::jsonb)",
      task_id, job_id
    ).first
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'passed')

    executor_call(:put, "/executor/trials/#{trial_id}/attachments/report.txt",
                  "hello from the trial", @token)

    # script/* attachments are shown in the Scripts table, not the Attachments panel
    executor_call(:put, "/executor/trials/#{trial_id}/attachments/scripts/main",
                  "script log", @token)

    # tree attachment appears in Tree Attachments panel
    executor_call(:put, "/executor/trials/#{trial_id}/tree-attachments/tree-report.txt",
                  "shared tree output", @token)

    visit "/trials/#{trial_id}"
    expect(page).to have_css 'h5', text: 'Attachments'
    expect(page).to have_css 'a code', text: 'report.txt'
    expect(page).not_to have_css '.page.trial a code', text: 'scripts/main'
    expect(page).to have_css 'h5', text: 'Tree Attachments'
    expect(page).to have_css 'a code', text: 'tree-report.txt'
  end

end
