require 'spec_helper'
require 'json'
require 'securerandom'
require 'digest'
require 'timeout'

# Trial scripts must run with stdin at EOF (/dev/null), not with the open,
# never-closed pipe a ProcessBuilder provides by default. Tools that read
# configuration from a non-tty stdin — `incus launch` is the case that bit
# leihs' container-build job — otherwise block until the script times out.
feature 'Script stdin is EOF' do

  STDIN_PROJECT_ID  = 'cider-ci-demo-project'
  STDIN_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'
  STDIN_TRAIT       = "stdin-test-#{SecureRandom.hex(4)}"

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id: STDIN_PROJECT_ID, name: 'Demo Project', git_url: 'local',
      branch_trigger_include_match: '^__none__$' # no auto-triggered demo jobs
    )

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       "stdin-executor-#{SecureRandom.hex(4)}",
      token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8],
      enabled:    true
    )

    log_dir = PROJECT_DIR.join('tmp/executor-logs')
    FileUtils.mkdir_p(log_dir)
    @executor_log_path = log_dir.join("executor-#{SecureRandom.hex(6)}.log").to_s
    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN'  => @executor_token,
        'CIDER_CI_SERVER_URL'      => http_base_url,
        'CIDER_CI_EXECUTOR_TRAITS' => STDIN_TRAIT },
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
  end

  def insert_stdin_job!
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    database[:commits].insert(
      id: STDIN_HEAD_COMMIT, tree_id: 'f' * 40,
      author_name: 'A', committer_name: 'A', subject: 'stdin fixture'
    )
    database[:jobs].insert(
      id: job_id, project_id: STDIN_PROJECT_ID, commit_id: STDIN_HEAD_COMMIT,
      key: 'stdin-eof', name: 'Stdin EOF', state: 'pending'
    )
    database[:tasks].insert(
      id: task_id, job_id: job_id, name: 'stdin-eof', state: 'pending', load: 1.0
    )
    spec_json = {
      name: 'stdin-eof',
      load: 1.0,
      traits: [STDIN_TRAIT],
      scripts: {
        # `read` returns non-zero immediately on EOF; on an open pipe it would
        # block until the script timeout turns the trial defective.
        'check-stdin' => {
          timeout: '20 Seconds',
          body: "#!/usr/bin/env bash\n" \
                "if read -r line; then echo \"unexpected stdin data: $line\"; exit 1; fi\n" \
                "echo 'stdin is EOF'\n"
        }
      }
    }.to_json
    database.run(
      "UPDATE tasks SET spec = '#{spec_json}'::jsonb, traits = '{#{STDIN_TRAIT}}'::text[] " \
      "WHERE id = '#{task_id}'"
    )
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'pending')
    trial_id
  end

  scenario 'a script reading stdin sees EOF at once and the trial passes' do
    trial_id = insert_stdin_job!
    state = nil
    Timeout.timeout(120) do
      loop do
        state = database[:trials][id: trial_id][:state]
        break if %w[passed failed defective aborted].include?(state)
        sleep 1
      end
    end
    expect(state).to eq 'passed'

    visit "/trials/#{trial_id}"
    expect(page).to have_css('h3 .badge', text: 'passed')
    expect(find('tr', text: 'check-stdin')).to have_css('.badge', text: 'passed')
  end
end
