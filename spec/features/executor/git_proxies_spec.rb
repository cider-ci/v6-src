require 'spec_helper'
require 'net/http'
require 'json'
require 'digest'
require 'securerandom'

# The dispatch payload carries git_proxies: {submodule commit => git URL on
# this server} for every submodule a configured repository holds, so
# executors fetch them here rather than from GitHub (which may throttle, or
# not have the repository at all). The demo project's submodule is the demo
# project itself at an older commit.
feature 'Executor dispatch git_proxies' do

  PROXIES_PROJECT_ID  = 'cider-ci-demo-project'
  PROXIES_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'
  PROXIES_SUB_COMMIT  = 'd841bd004ba820c4f8d4568b5198904a7ec6c708' # gitlink "submodule"

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: PROXIES_PROJECT_ID, name: 'Demo Project', git_url: 'local',
                                   branch_trigger_include_match: '^__none__$')
    @token = SecureRandom.hex(32)
    database[:executors].insert(name: 'proxies-executor', token_hash: Digest::SHA256.hexdigest(@token),
                                token_part: @token[0, 8], enabled: true)
    # the demo job's tasks require the bash trait
    database.run("UPDATE executors SET traits = '{bash}' WHERE name = 'proxies-executor'")
  end

  def sync!
    uri = URI("#{http_base_url}/executor/sync")
    req = Net::HTTP::Post.new(uri)
    req['Authorization'] = "Bearer #{@token}"
    req['Accept'] = 'application/json'
    req['Content-Type'] = 'application/json'
    req.body = { available_load: 1.0 }.to_json
    JSON.parse(Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }.body)
  end

  scenario 'maps the submodule commit to this server when a configured repository holds it' do
    visit "/projects/#{PROXIES_PROJECT_ID}/commits/#{PROXIES_HEAD_COMMIT}/jobs"
    find('tr', text: 'Submodule Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    trial = sync!['trials_to_execute'].first
    expect(trial).not_to be_nil
    expect(trial['git_url']).to eq "#{http_base_url}/projects/#{PROXIES_PROJECT_ID}/git"
    proxies = trial['git_proxies']
    expect(proxies).to include(PROXIES_SUB_COMMIT => "#{http_base_url}/projects/#{PROXIES_PROJECT_ID}/git")
    # the walk is recursive: the submodule commit (the demo project itself at an
    # older revision) has submodules of its own, all held by this repository
    expect(proxies.size).to be > 1
    expect(proxies.values.uniq).to eq ["#{http_base_url}/projects/#{PROXIES_PROJECT_ID}/git"]
    expect(proxies.keys).to all(match(/\A[0-9a-f]{40}\z/))
  end
end
