require 'spec_helper'
require 'json'
require 'securerandom'
require 'digest'
require 'timeout'

# The working directory's `origin` remote must be the project's real git URL
# (repositories.git_url), as in the legacy executor — not the CIDER-CI server's
# git proxy, which needs the executor token. Project scripts run e.g.
# `git fetch origin master` (leihs meta job); against the proxy that fails with
# "could not read Username".
feature 'Working dir origin remote' do

  ORIGIN_PROJECT_ID  = 'cider-ci-demo-project'
  ORIGIN_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'
  ORIGIN_TRAIT       = "origin-test-#{SecureRandom.hex(4)}"
  ORIGIN_GIT_URL     = 'https://example.invalid/demo/project.git'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    # A git_url that is not fetchable: the executor must still be able to
    # check the commit out (it clones via the server proxy), and only set
    # `origin` to this URL afterwards.
    database[:repositories].insert(
      id: ORIGIN_PROJECT_ID, name: 'Demo Project', git_url: ORIGIN_GIT_URL,
      branch_trigger_include_match: '^__none__$' # no auto-triggered demo jobs
    )

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       "origin-executor-#{SecureRandom.hex(4)}",
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
        'CIDER_CI_EXECUTOR_TRAITS' => ORIGIN_TRAIT },
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

  def insert_origin_job!
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    database[:commits].insert(
      id: ORIGIN_HEAD_COMMIT, tree_id: 'f' * 40,
      author_name: 'A', committer_name: 'A', subject: 'origin fixture'
    )
    database[:jobs].insert(
      id: job_id, project_id: ORIGIN_PROJECT_ID, commit_id: ORIGIN_HEAD_COMMIT,
      key: 'origin-check', name: 'Origin Check', state: 'pending'
    )
    database[:tasks].insert(
      id: task_id, job_id: job_id, name: 'origin-check', state: 'pending', load: 1.0
    )
    spec_json = {
      name: 'origin-check',
      load: 1.0,
      traits: [ORIGIN_TRAIT],
      scripts: {
        'show-origin' => {
          timeout: '30 Seconds',
          body: "#!/usr/bin/env bash\nset -euo pipefail\n" \
                "echo \"ORIGIN=$(git remote get-url origin)\"\n" \
                "git rev-parse HEAD\n"
        }
      }
    }.to_json
    database.run(Sequel.lit(
      'UPDATE tasks SET spec = ?::jsonb, traits = ?::text[] WHERE id = ?',
      spec_json, "{#{ORIGIN_TRAIT}}", task_id
    ))
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'pending')
    trial_id
  end

  scenario "origin is the repository's git_url, checkout still works via the server" do
    trial_id = insert_origin_job!
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
            .where(trial_id: trial_id, path: 'scripts/show-origin')
            .get(:content)
    expect(log).not_to be_nil
    text = log.is_a?(Sequel::SQL::Blob) ? log.to_s : log.to_s
    expect(text).to include("ORIGIN=#{ORIGIN_GIT_URL}")
    expect(text).to include(ORIGIN_HEAD_COMMIT)
  end
end
