require 'spec_helper'
require 'json'
require 'securerandom'
require 'digest'
require 'timeout'

# An executor with accepted GPG keys verifies the signature of the checked
# out commit itself, before any script runs: the server's dispatch decision
# (dispatcher_spec) is not trusted.
feature 'Executor accepted GPG keys' do

  let(:project_id) { "gpg-exec-#{SecureRandom.hex(4)}" }
  let(:trait)      { "gpg-test-#{SecureRandom.hex(4)}" }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    create_gpg_signed_repo(email: 'signer@cider-ci.example')
    # the server verifies the commit with this global key, so it dispatches
    database[:gpg_keys].insert(fingerprint: @fingerprint, name: 'Signer', ascii_key: @ascii_key)
    database[:repositories].insert(id: project_id, name: 'GPG exec', git_url: @git_url,
                                   branch_trigger_include_match: '^__none__$')
    reload_server_repository_state
    @commit = wait_until(30) do
      database[:commits].join(:branches, current_commit_id: :id)
        .where(Sequel[:branches][:repository_id] => project_id)
        .select(Sequel[:commits][:id], Sequel[:commits][:signature_fingerprint]).first
    end
    expect(@commit[:signature_fingerprint]).to eq @fingerprint
  end

  after :each do
    stop_executor
    remove_gpg_signed_repo
  end

  def start_executor(accepted_gpg_keys)
    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name: "gpg-executor-#{SecureRandom.hex(4)}", token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8], enabled: true)
    log_dir = PROJECT_DIR.join('tmp/executor-logs')
    FileUtils.mkdir_p(log_dir)
    @executor_log_path = log_dir.join("executor-#{SecureRandom.hex(6)}.log").to_s
    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN' => @executor_token,
        'CIDER_CI_EXECUTOR_VERIFY_ORIGIN' => 'false',
        'CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES' => '["^.*$"]',
        'CIDER_CI_EXECUTOR_ACCEPTED_GPG_KEYS' => accepted_gpg_keys.to_json,
        'CIDER_CI_SERVER_URL' => http_base_url,
        'CIDER_CI_EXECUTOR_TRAITS' => trait },
      PROJECT_DIR.join('bin/executor-run').to_s,
      out: @executor_log_path, err: @executor_log_path)
    Timeout.timeout(40) do
      sleep 0.5 until File.read(@executor_log_path).include?('Executor sync loop starting')
    end
  end

  def stop_executor
    return unless @executor_pid
    Process.kill('TERM', @executor_pid) rescue nil
    Process.wait(@executor_pid) rescue nil
  end

  def insert_trial!
    job_id = database[:jobs].insert(id: SecureRandom.uuid, project_id: project_id, commit_id: @commit[:id],
                                    key: 'gpg-check', name: 'GPG Check', state: 'pending')
    task_id = database[:tasks].insert(id: SecureRandom.uuid, job_id: job_id, name: 'gpg-check',
                                      state: 'pending', load: 1.0)
    spec_json = { name: 'gpg-check', load: 1.0, traits: [trait],
                  scripts: { 'main' => { timeout: '30 Seconds', body: "#!/usr/bin/env bash\ngit rev-parse HEAD\n" } } }.to_json
    database.run(Sequel.lit('UPDATE tasks SET spec = ?::jsonb, traits = ?::text[] WHERE id = ?',
                            spec_json, "{#{trait}}", task_id))
    database[:trials].insert(id: SecureRandom.uuid, task_id: task_id, state: 'pending')
  end

  def wait_for_terminal_state(trial_id)
    wait_until(90) do
      state = database[:trials][id: trial_id][:state]
      state if %w[passed failed defective aborted].include?(state)
    end
  end

  scenario 'a commit signed with an accepted key runs' do
    start_executor([@ascii_key])
    trial_id = insert_trial!
    expect(wait_for_terminal_state(trial_id)).to eq 'passed'
    expect(File.read(@executor_log_path)).not_to include 'not signed with an accepted GPG key'
  end

  scenario 'a commit the server wrongly claims to be verified fails on the executor, no script runs' do
    # the executor accepts the fixture key; the server is made to claim the
    # commit (signed by the generated key) was verified by it
    fixture_fingerprint = '247047366DDD1587BF63A6043F8629952915F2B7'
    database[:commits].where(id: @commit[:id]).update(signature_fingerprint: fixture_fingerprint)
    start_executor([STATIC_GPG_KEY])
    trial_id = insert_trial!
    expect(wait_for_terminal_state(trial_id)).to eq 'failed'
    trial = database[:trials][id: trial_id]
    expect(trial[:error]).to include 'not signed with an accepted GPG key'
    expect(trial[:error]).to include 'signed with another key'
    expect(database[:trial_attachments].where(trial_id: trial_id).count).to eq 0

    visit "/trials/#{trial_id}"
    expect(page).to have_css '.alert-danger', text: 'not signed with an accepted GPG key'
  end

  scenario 'the executor page lists the keys, the job page links the matching executors' do
    start_executor([@ascii_key, 'not a key'])
    executor = wait_until(20) do
      e = database[:executors].where(token_part: @executor_token[0, 8]).first
      e if JSON.parse(e[:accepted_gpg_keys].to_s).length == 2
    end
    visit "/executors/#{executor[:id]}"
    within('.accepted-gpg-keys') do
      expect(page).to have_content 'Signer'
      expect(page).to have_content @fingerprint
      expect(page).to have_content 'global key'
      expect(page).to have_content 'no public key found – this executor accepts no commit'
    end

    job_id = database[:jobs].insert(id: SecureRandom.uuid, project_id: project_id, commit_id: @commit[:id],
                                    key: 'gpg-ui', name: 'GPG UI', state: 'pending')
    visit "/projects/#{project_id}/commits/#{@commit[:id]}/jobs/#{job_id}"
    within('.commit-signature') do
      expect(page).to have_content 'signed by Signer'
      click_on 'Match executors'
    end
    expect(page).to have_current_path(/signature=#{@fingerprint}/)
    expect(page).to have_css '.signature-filter', text: @fingerprint
    expect(page).to have_css 'td.gpg-keys', text: 'invalid'
  end
end
