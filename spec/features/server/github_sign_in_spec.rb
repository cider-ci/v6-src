require 'spec_helper'
require 'securerandom'

# Sign in with GitHub against a fake GitHub (OAuth + API). The server reads
# the configuration from tmp/github-sign-in.yml (see bin/server-run).
feature 'GitHub sign-in' do
  include_context 'with fake github'

  let(:config_file) { PROJECT_DIR.join('tmp', 'github-sign-in.yml') }
  let(:github_id) { 4242 }

  before :each do
    # an admin must exist, otherwise the SPA redirects to the initial setup
    @admin = FactoryBot.create(:admin)
    write_config(rules: [{ 'organization' => 'Example-Org', 'team' => 'CI Admins', 'admin' => true },
                         { 'organization' => 'example-org' }])
    @fake_github.stub_post('/login/oauth/access_token',
                           { access_token: 'fake-token', token_type: 'bearer', scope: 'read:org,user:email' })
    stub_account
  end

  after :each do
    FileUtils.rm_f(config_file)
  end

  def write_config(rules:)
    FileUtils.mkdir_p(config_file.dirname)
    File.write(config_file, {
      'client_id' => 'fake-client-id',
      'client_secret' => 'fake-client-secret',
      'oauth_base_url' => "#{@fake_github.url}/login/oauth",
      'api_endpoint' => @fake_github.url,
      'callback_base_url' => http_base_url,
      'rules' => rules
    }.to_yaml)
  end

  def stub_account(login: 'octo-cat', name: 'Octo Cat', orgs: ['Example-Org'], teams: ['ci-admins'],
                   emails: [['octo@example.org', true, true], ['unverified@example.org', false, false]],
                   gpg_keys: [])
    @fake_github.stub_get('/user', { id: github_id, login: login, name: name })
    @fake_github.stub_get('/user/memberships/orgs',
                          orgs.map { |o| { state: 'active', organization: { login: o } } })
    @fake_github.stub_get('/user/teams',
                          teams.map { |t| { slug: t, name: t.tr('-', ' ').upcase, organization: { login: 'example-org' } } })
    @fake_github.stub_get('/user/emails',
                          emails.map { |e, verified, primary| { email: e, verified: verified, primary: primary } })
    @fake_github.stub_get("/users/#{login}/gpg_keys",
                          gpg_keys.map.with_index { |k, i| { key_id: "KEY#{i}", raw_key: k, revoked: false } })
  end

  def sign_in_with_github
    visit '/sign-in'
    click_on 'Sign in with GitHub'
  end

  def github_user
    database[:users].where(github_id: github_id).first
  end

  scenario 'a member of the admin team signs in as admin; emails and GPG keys are imported' do
    stub_account(gpg_keys: [STATIC_GPG_KEY])
    sign_in_with_github
    expect(page).to have_css '.user-uid', text: 'octo-cat'

    user = github_user
    expect(user[:github_login]).to eq 'octo-cat'
    expect(user[:name]).to eq 'Octo Cat'
    expect(user[:login]).to be_nil
    expect(user[:is_admin]).to be true
    emails = database[:email_addresses].where(user_id: user[:id]).all
    expect(emails.map { |e| [e[:email_address], e[:is_primary], e[:source]] })
      .to eq [['octo@example.org', true, 'github']]
    key = database[:gpg_keys].where(user_id: user[:id]).first
    expect(key[:source]).to eq 'github'
    expect(key[:fingerprint]).to be_present

    token_request = github_requests.find { |r| r.path == '/login/oauth/access_token' }
    expect(token_request.body).to include 'code=fake-code', 'client_secret=fake-client-secret'
    api_request = github_requests.find { |r| r.path == '/user' }
    expect(api_request.headers['authorization']).to eq 'Bearer fake-token'

    visit "/users/#{user[:id]}"
    expect(page).to have_css '.github-login', text: 'octo-cat'
    visit "/users/#{user[:id]}/email-addresses/"
    expect(page).to have_css '.source-github'
    visit "/users/#{user[:id]}/gpg-keys/"
    expect(page).to have_css '.source-github'
  end

  scenario 'an organization member signs in as a user and returns to the requested page' do
    stub_account(teams: [])
    visit '/projects/'
    expect(page).to have_current_path(%r{/sign-in\?return-to=})
    click_on 'Sign in with GitHub'
    expect(page).to have_current_path('/projects/')
    expect(page).to have_css '.user-uid', text: 'octo-cat'
    expect(github_user[:is_admin]).to be false
  end

  scenario 'a GitHub account without a matching organization or team is refused' do
    stub_account(orgs: ['other-org'], teams: [])
    sign_in_with_github
    expect(page).to have_css '.github-sign-in-error', text: 'not a member'
    expect(page).not_to have_css '.user-uid'
    expect(github_user).to be_nil
  end

  scenario 'a pending organization membership does not count' do
    stub_account(teams: [])
    @fake_github.stub_get('/user/memberships/orgs',
                          [{ state: 'pending', organization: { login: 'Example-Org' } }])
    sign_in_with_github
    expect(page).to have_css '.github-sign-in-error', text: 'not a member'
  end

  scenario 'the next sign-in updates the account; manual and foreign rows are kept' do
    stub_account(gpg_keys: [STATIC_GPG_KEY],
                 emails: [['octo@example.org', true, true], ['old@example.org', true, false]])
    sign_in_with_github
    expect(page).to have_css '.user-uid', text: 'octo-cat'
    user = github_user
    expect(user[:is_admin]).to be true
    database[:email_addresses].insert(user_id: user[:id], email_address: 'manual@example.org')
    other = FactoryBot.create(:user)
    database[:email_addresses].insert(user_id: other.id, email_address: 'taken@example.org')

    stub_account(teams: [], gpg_keys: [],
                 emails: [['octo@example.org', true, true], ['taken@example.org', true, false]])
    page.driver.browser.manage.delete_all_cookies
    sign_in_with_github
    expect(page).to have_css '.user-uid', text: 'octo-cat'

    user = github_user
    expect(user[:is_admin]).to be false
    expect(database[:email_addresses].where(user_id: user[:id]).select_map(:email_address).sort)
      .to eq ['manual@example.org', 'octo@example.org']
    expect(database[:email_addresses].where(email_address: 'taken@example.org').first[:user_id])
      .to eq other.id
    expect(database[:gpg_keys].where(user_id: user[:id]).count).to eq 0
    expect(database[:users].where(github_id: github_id).count).to eq 1
  end

  scenario 'a callback with a wrong state is refused' do
    visit "/sign-in/github/callback?code=fake-code&state=#{SecureRandom.hex(8)}"
    expect(page).to have_css '.github-sign-in-error', text: 'expired'
    expect(github_requests).to be_empty
  end

  scenario 'cancelling on GitHub shows a message' do
    visit '/sign-in/github/callback?error=access_denied&state=x'
    expect(page).to have_css '.github-sign-in-error', text: 'cancelled'
  end

  scenario 'without configuration there is no GitHub sign-in' do
    FileUtils.rm_f(config_file)
    visit '/sign-in'
    expect(page).to have_field 'password'
    expect(page).not_to have_link 'Sign in with GitHub'
  end

  context 'commit signatures' do
    before :each do
      create_gpg_signed_repo(email: 'octo@example.org', name: 'Octo Cat')
      @project_id = "gh-gpg-#{SecureRandom.hex(4)}"
      database[:repositories].insert(id: @project_id, name: 'GitHub GPG', git_url: @git_url)
      reload_server_repository_state
    end

    after :each do
      remove_gpg_signed_repo
    end

    scenario 'a commit signed with a GPG key of the GitHub account becomes verified' do
      commit = wait_until(30) do
        database[:commits].join(:branches, current_commit_id: :id)
          .where(Sequel[:branches][:repository_id] => @project_id)
          .select(Sequel[:commits][:id], Sequel[:commits][:signature_fingerprint]).first
      end
      expect(commit[:signature_fingerprint]).to be_nil

      stub_account(gpg_keys: [@ascii_key])
      sign_in_with_github
      expect(page).to have_css '.user-uid', text: 'octo-cat'

      expect(database[:commits].where(id: commit[:id]).get(:signature_fingerprint)).to eq @fingerprint
      visit "/projects/#{@project_id}/commits/#{commit[:id]}"
      expect(page).to have_css('.alert-success', text: /Signed/)
    end
  end
end
