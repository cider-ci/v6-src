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

end
