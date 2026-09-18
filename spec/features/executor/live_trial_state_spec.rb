require 'spec_helper'
require 'digest'
require 'fileutils'
require 'json'
require 'securerandom'
require 'timeout'

# End-to-end test for live (intermediate) trial state:
# while a script sleeps, the trial page must show the current per-script
# states, the executor, the working directory, the real environment
# variables, and the actually assigned port — updated live via the
# 250ms auto-refresh, without any manual reload.
#
# Isolation: this spec runs a dedicated executor with a UNIQUE trait and
# gives its task that trait. The demo project's auto-triggered jobs all
# require the 'Bash' trait, so they find no matching executor, stay
# pending, and generate no load — the slumber trial runs alone and its
# timing is deterministic (avoids the thundering-herd starvation that a
# shared 'Bash' executor would suffer).
feature 'Live trial state' do
  UNIQUE_TRAIT = 'livestate-only'
  PROJECT_ID   = 'cider-ci-demo-project'
  HEAD_COMMIT  = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    # branch_trigger_include_match matches no branch, so the demo project's
    # jobs are never auto-triggered by the repository fetch loop. This keeps
    # the executor free to run only the job this spec inserts — the ~22
    # auto-triggered demo trials would otherwise saturate it and starve the
    # slumber trial (their tasks have empty traits, which match any executor,
    # so a unique executor trait cannot exclude them).
    database[:repositories].insert(
      id: PROJECT_ID, name: 'Demo Project', git_url: 'local',
      branch_trigger_include_match: '^__none__$'
    )

    @executor_name  = "livestate-executor-#{SecureRandom.hex(4)}"
    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       @executor_name,
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
        'CIDER_CI_EXECUTOR_TRAITS' => UNIQUE_TRAIT },
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

  def insert_live_state_job!
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    database[:commits].insert(
      id: HEAD_COMMIT, tree_id: 'f' * 40,
      author_name: 'A', committer_name: 'A', subject: 'live-state fixture'
    )
    database[:jobs].insert(
      id: job_id, project_id: PROJECT_ID, commit_id: HEAD_COMMIT,
      key: 'live-state', name: 'Live State', state: 'pending'
    )
    database[:tasks].insert(
      id: task_id, job_id: job_id, name: 'live-state', state: 'pending', load: 1.0
    )
    spec_json = {
      name: 'live-state',
      load: 1.0,
      traits: [UNIQUE_TRAIT],
      scripts: {
        prepare:  { body: "#!/usr/bin/env bash\ntrue\n" },
        slumber:  { body: "#!/usr/bin/env bash\nsleep 15\n",
                    start_when: { prepare_done: { script_key: 'prepare' } } },
        finalize: { body: "#!/usr/bin/env bash\ntrue\n",
                    start_when: { slumber_done: { script_key: 'slumber' } } }
      },
      ports: { TEST_PORT: { min: 20_000, max: 20_999 } },
      environment_variables: { LIVE_TEST_MARKER: 'live-42' }
    }.to_json
    database.run(
      "UPDATE tasks SET spec = '#{spec_json}'::jsonb, traits = '{#{UNIQUE_TRAIT}}'::text[] " \
      "WHERE id = '#{task_id}'"
    )
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'pending')

    { job_id: job_id, task_id: task_id, trial_id: trial_id }
  end

  scenario 'trial page shows intermediate script states and exec info while a script sleeps' do
    h = insert_live_state_job!
    visit "/trials/#{h[:trial_id]}"

    # While "slumber" sleeps: prepare passed, slumber executing, finalize pending.
    # The executor reports live states ~3s after scripts start; generous wait
    # covers dispatch + git checkout.
    expect(find('tr', text: 'slumber')).to have_css('.badge', text: 'executing', wait: 60)
    expect(find('tr', text: 'prepare')).to have_css('.badge', text: 'passed')
    expect(find('tr', text: 'finalize')).to have_css('.badge', text: 'pending')
    expect(page).to have_css('h3 .badge', text: 'executing')

    # Debug information reported by the executor while still running:
    expect(page).to have_content 'Debug Information'
    expect(page).to have_content(@executor_name)
    # Real working directory (contains the trial id)
    expect(page).to have_css('code', text: /cider-ci-#{h[:trial_id]}/)
    # Real environment variables
    expect(page).to have_content 'LIVE_TEST_MARKER'
    expect(page).to have_content 'live-42'
    # Actually assigned port: a concrete number from the range, not the range text
    port_dd = find(:xpath, "//dt[code[text()='TEST_PORT']]/following-sibling::dd[1]")
    expect(port_dd.text).to match(/\A\d+\z/)
    expect(port_dd.text.to_i).to be_between(20_000, 20_999)

    # After slumber finishes everything passes and propagates up to the job.
    expect(page).to have_css('h3 .badge', text: 'passed', wait: 90)
    expect(find('tr', text: 'finalize')).to have_css('.badge', text: 'passed')
    expect(database[:jobs][id: h[:job_id]][:state]).to eq 'passed'
    expect(database[:tasks][id: h[:task_id]][:state]).to eq 'passed'
  end
end
