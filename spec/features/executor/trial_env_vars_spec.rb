require 'spec_helper'
require 'json'
require 'securerandom'
require 'digest'
require 'timeout'

# Legacy parity: every trial gets CIDER_CI_JOB_ID, CIDER_CI_TASK_ID and
# CIDER_CI_TREE_ID (the git tree id of the job's commit). Project configs use
# them in scripts and in `{{...}}` templates, e.g. leihs'
# `exclusive_executor_resource: 'build-leihs-admin-jar-{{CIDER_CI_TREE_ID}}'`.
feature 'Trial environment variables' do

  ENVV_PROJECT_ID  = 'cider-ci-demo-project'
  ENVV_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'
  ENVV_TREE_ID     = 'e' * 40
  ENVV_TRAIT       = "envvars-test-#{SecureRandom.hex(4)}"

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id: ENVV_PROJECT_ID, name: 'Demo Project',
      git_url: 'https://example.invalid/demo/project.git',
      branch_trigger_include_match: '^__none__$' # no auto-triggered demo jobs
    )

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       "envvars-executor-#{SecureRandom.hex(4)}",
      token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8],
      enabled:    true
    )

    log_dir = PROJECT_DIR.join('tmp/executor-logs')
    FileUtils.mkdir_p(log_dir)
    @executor_log_path = log_dir.join("executor-#{SecureRandom.hex(6)}.log").to_s
    @executor_pid = Process.spawn(
      { 'CIDER_CI_EXECUTOR_TOKEN'  => @executor_token,
        'CIDER_CI_EXECUTOR_VERIFY_ORIGIN' => 'false', # placeholder git_url, no real origin to verify against
        'CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES' => '["^.*$"]', # not the host's /etc/cider-ci/accepted-repositories.yml
        'CIDER_CI_SERVER_URL'      => http_base_url,
        'CIDER_CI_EXECUTOR_TRAITS' => ENVV_TRAIT },
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

  def insert_envvars_job!
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    database[:commits].insert(
      id: ENVV_HEAD_COMMIT, tree_id: ENVV_TREE_ID,
      author_name: 'A', committer_name: 'A', subject: 'envvars fixture'
    )
    database[:jobs].insert(
      id: job_id, project_id: ENVV_PROJECT_ID, commit_id: ENVV_HEAD_COMMIT,
      key: 'envvars-check', name: 'Env Vars Check', state: 'pending'
    )
    database[:tasks].insert(
      id: task_id, job_id: job_id, name: 'envvars-check', state: 'pending', load: 1.0
    )
    spec_json = {
      name: 'envvars-check',
      load: 1.0,
      traits: [ENVV_TRAIT],
      scripts: {
        'show-env' => {
          timeout: '30 Seconds',
          exclusive_executor_resource: 'envvars-{{CIDER_CI_TREE_ID}}',
          body: "#!/usr/bin/env bash\nset -euo pipefail\n" \
                "echo \"JOB=${CIDER_CI_JOB_ID}\"\n" \
                "echo \"TASK=${CIDER_CI_TASK_ID}\"\n" \
                "echo \"TREE=${CIDER_CI_TREE_ID}\"\n"
        }
      }
    }.to_json
    database.run(Sequel.lit(
      'UPDATE tasks SET spec = ?::jsonb, traits = ?::text[] WHERE id = ?',
      spec_json, "{#{ENVV_TRAIT}}", task_id
    ))
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'pending')
    [job_id, task_id, trial_id]
  end

  scenario 'scripts see the job, task and tree id of their trial' do
    job_id, task_id, trial_id = insert_envvars_job!
    state = nil
    Timeout.timeout(120) do
      loop do
        state = database[:trials][id: trial_id][:state]
        break if %w[passed failed defective aborted].include?(state)
        sleep 1
      end
    end
    expect(state).to eq 'passed'

    log = database[:trial_attachments]
            .where(trial_id: trial_id, path: 'scripts/show-env')
            .get(:content)
    expect(log).not_to be_nil
    text = log.to_s
    expect(text).to include("JOB=#{job_id}")
    expect(text).to include("TASK=#{task_id}")
    expect(text).to include("TREE=#{ENVV_TREE_ID}")
  end
end
