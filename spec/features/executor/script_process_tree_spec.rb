require 'spec_helper'
require 'json'
require 'securerandom'
require 'digest'
require 'timeout'

# A script that is killed (timeout, abort, terminate_when) must take its
# whole process tree with it. Killing only the shell left a test server
# started with `&` (and rspec with its Firefox) running as orphans after the
# working dir was gone, logging into a deleted file until /tmp was full.
feature 'Killed scripts take their process tree with them' do

  PTREE_PROJECT_ID  = 'cider-ci-demo-project'
  PTREE_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'
  PTREE_TRAIT       = "ptree-test-#{SecureRandom.hex(4)}"

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id: PTREE_PROJECT_ID, name: 'Demo Project', git_url: 'local',
      branch_trigger_include_match: '^__none__$'
    )

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       "ptree-executor-#{SecureRandom.hex(4)}",
      token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8],
      enabled:    true
    )

    log_dir = PROJECT_DIR.join('tmp/executor-logs')
    FileUtils.mkdir_p(log_dir)
    @executor_log_path = log_dir.join("executor-#{SecureRandom.hex(6)}.log").to_s
    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN'  => @executor_token,
        'CIDER_CI_EXECUTOR_VERIFY_ORIGIN' => 'false',
        'CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES' => '["^.*$"]', # not the host's /etc/cider-ci/accepted-repositories.yml
        'CIDER_CI_EXECUTOR_ACCEPTED_GPG_KEYS' => '[]', # not the host's /etc/cider-ci/accepted-gpg-keys.yml
        'CIDER_CI_SERVER_URL'      => http_base_url,
        'CIDER_CI_EXECUTOR_TRAITS' => PTREE_TRAIT },
      PROJECT_DIR.join('bin/executor-run').to_s,
      out: @executor_log_path, err: @executor_log_path
    )
    Timeout.timeout(40) do
      sleep 0.5 until File.read(@executor_log_path).include?('Executor sync loop starting')
    end
  end

  after :each do
    Process.kill('TERM', @executor_pid) rescue nil
    Process.wait(@executor_pid)         rescue nil
    # never leave the marker process behind, whatever the outcome
    system('pkill', '-f', @marker, out: File::NULL, err: File::NULL) if @marker
  end

  # Without a shell: `sh -c "pgrep -f marker"` matches that very shell on
  # Debian (dash does not exec the last command), so the marker always
  # looked alive there.
  def marker_running?
    system('pgrep', '-f', @marker, out: File::NULL)
  end

  def insert_job!
    @marker  = "cider-ci-orphan-#{SecureRandom.hex(6)}"
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    database[:commits].insert(
      id: PTREE_HEAD_COMMIT, tree_id: 'f' * 40,
      author_name: 'A', committer_name: 'A', subject: 'process tree fixture'
    )
    database[:jobs].insert(
      id: job_id, project_id: PTREE_PROJECT_ID, commit_id: PTREE_HEAD_COMMIT,
      key: 'ptree', name: 'Process tree', state: 'pending'
    )
    database[:tasks].insert(
      id: task_id, job_id: job_id, name: 'ptree', state: 'pending', load: 1.0
    )
    spec_json = {
      name: 'ptree',
      load: 1.0,
      traits: [PTREE_TRAIT],
      scripts: {
        # a "server" started in the background (recognisable by its argv[0]),
        # then the script overruns its timeout
        'serve' => {
          timeout: '5 Seconds',
          body: "#!/usr/bin/env bash\n" \
                "bash -c 'exec -a #{@marker} sleep 300' &\n" \
                "echo \"started #{@marker}\"\n" \
                "sleep 120\n"
        }
      }
    }.to_json
    database.run(Sequel.lit(
      'UPDATE tasks SET spec = ?::jsonb, traits = ?::text[] WHERE id = ?',
      spec_json, "{#{PTREE_TRAIT}}", task_id
    ))
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'pending')
    trial_id
  end

  scenario 'a background process of a timed out script is killed too' do
    trial_id = insert_job!
    Timeout.timeout(60) { sleep 0.5 until marker_running? }

    state = nil
    Timeout.timeout(120) do
      loop do
        state = database[:trials][id: trial_id][:state]
        break if %w[passed failed defective aborted].include?(state)
        sleep 1
      end
    end
    expect(state).to eq 'defective'
    result = JSON.parse(database[:trials][id: trial_id][:result].to_s)
    expect(result['scripts']['serve']['error']).to include 'timed out'

    Timeout.timeout(15) { sleep 0.5 while marker_running? }
    expect(marker_running?).to be false
  end
end
