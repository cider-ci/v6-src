require 'spec_helper'
require 'net/http'
require 'json'
require 'digest'
require 'securerandom'

feature 'Executor Sync' do

  EXECUTOR_PROJECT_ID  = 'cider-ci-demo-project'
  EXECUTOR_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  def create_executor!(name: 'test-executor')
    token = SecureRandom.hex(32)
    database[:executors].insert(
      name: name,
      token_hash: Digest::SHA256.hexdigest(token),
      token_part: token[0, 8],
      enabled: true
    )
    token
  end

  def api_call(method, path, body, token)
    uri = URI("#{http_base_url}#{path}")
    req = case method
          when :post  then Net::HTTP::Post.new(uri)
          when :patch then Net::HTTP::Patch.new(uri)
          when :get   then Net::HTTP::Get.new(uri)
          end
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req['Authorization'] = "Bearer #{token}"
    req.body = body.to_json if body
    Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      EXECUTOR_PROJECT_ID,
      name:    'Demo Project',
      git_url: 'local'
    )

    @token = create_executor!
  end

  scenario 'executor syncs a trial and reports it passed' do
    # Trigger a job via the browser
    visit "/projects/#{EXECUTOR_PROJECT_ID}/commits/#{EXECUTOR_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    # Sync: executor claims the pending trial
    resp = api_call(:post, '/executor/sync',
                    { available_load: 1.0, trials: [] }, @token)
    expect(resp.code.to_i).to eq(200)
    sync_data = JSON.parse(resp.body)
    expect(sync_data['trials_to_execute']).not_to be_empty
    trial = sync_data['trials_to_execute'].first
    trial_id = trial['id']

    # PATCH trial to executing
    resp = api_call(:patch, "/executor/trials/#{trial_id}",
                    { state: 'executing' }, @token)
    expect(resp.code.to_i).to eq(200)

    # PATCH trial to passed
    resp = api_call(:patch, "/executor/trials/#{trial_id}",
                    { state: 'passed' }, @token)
    expect(resp.code.to_i).to eq(200)

    # Verify state propagation on the job detail page
    find('a code', text: 'introduction-demo').click
    expect(page).to have_css '.badge', text: 'passed', minimum: 2
  end

end
