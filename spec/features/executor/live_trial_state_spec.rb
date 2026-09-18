require 'spec_helper'
require 'json'
require 'securerandom'

# End-to-end test for live (intermediate) trial state:
# while a script sleeps, the trial page must show the current per-script
# states, the executor, the working directory, the real environment
# variables, and the actually assigned port — updated live via the
# 250ms auto-refresh, without any manual reload.
feature 'Live trial state' do
  include_context 'with live executor'

  def insert_live_state_job!
    job_id   = SecureRandom.uuid
    task_id  = SecureRandom.uuid
    trial_id = SecureRandom.uuid

    # The commit exists in the demo-project bare repo, so the executor can
    # check it out; the row just needs to exist for the FK.
    database[:commits].insert(
      id: head_commit, tree_id: 'f' * 40,
      author_name: 'A', committer_name: 'A', subject: 'live-state fixture'
    )
    database[:jobs].insert(
      id: job_id, project_id: project_id, commit_id: head_commit,
      key: 'live-state', name: 'Live State', state: 'pending'
    )
    database[:tasks].insert(
      id: task_id, job_id: job_id, name: 'live-state', state: 'pending', load: 1.0
    )
    spec_json = {
      name: 'live-state',
      load: 1.0,
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
    database.run("UPDATE tasks SET spec = '#{spec_json}'::jsonb WHERE id = '#{task_id}'")
    database[:trials].insert(id: trial_id, task_id: task_id, state: 'pending')

    { job_id: job_id, task_id: task_id, trial_id: trial_id }
  end

  scenario 'trial page shows intermediate script states and exec info while a script sleeps' do
    h = insert_live_state_job!
    visit "/trials/#{h[:trial_id]}"

    # While "slumber" sleeps: prepare passed, slumber executing, finalize pending.
    # The executor reports live states ~3s after scripts start; generous wait
    # covers dispatch + git checkout on a loaded machine.
    expect(find('tr', text: 'slumber')).to have_css('.badge', text: 'executing', wait: 60)
    expect(find('tr', text: 'prepare')).to have_css('.badge', text: 'passed')
    expect(find('tr', text: 'finalize')).to have_css('.badge', text: 'pending')
    expect(page).to have_css('h3 .badge', text: 'executing')

    # Debug information reported by the executor while still running:
    expect(page).to have_content 'Debug Information'
    # Executor name
    expect(page).to have_content(database[:executors].get(:name))
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
