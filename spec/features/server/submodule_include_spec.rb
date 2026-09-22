require 'spec_helper'

# Submodule includes in the project configuration:
#   include:
#     - path: cider-ci/data/m2.yml
#       submodule: ["submodule"]
# must be read from the submodule's repository at the commit the parent's
# gitlink points to (and precedence follows include order). Previously v6
# ignored `submodule:` and read the path from the parent commit; when the file
# didn't exist there the include was silently dropped — which is how leihs'
# submodule-included database.yml vanished and its tasks ran without a DB.
#
# The demo project's submodules are self-referential (same repo, older
# commits), exercising the "parent repository contains the gitlink commit"
# fallback of the resolver. Expansion happens server-side at job creation,
# so this asserts on the stored task spec — no executor needed.
feature 'Submodule includes' do

  SUBMOD_PROJECT_ID  = 'cider-ci-demo-project'
  SUBMOD_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: SUBMOD_PROJECT_ID, name: 'Demo Project', git_url: 'local',
    branch_trigger_include_match: '^__none__$') # no auto-triggered demo jobs: specs click Run themselves
    visit "/projects/#{SUBMOD_PROJECT_ID}/commits/#{SUBMOD_HEAD_COMMIT}/jobs"
  end

  def task_env(job_key, task_name)
    database.fetch(<<~SQL, job_key, task_name).first
      SELECT t.spec->'environment_variables'->>'A' AS a,
             t.spec->'environment_variables'->>'B' AS b,
             t.spec->'environment_variables'->>'C' AS c
      FROM tasks t JOIN jobs j ON j.id = t.job_id
      WHERE j.key = ? AND t.name = ?
    SQL
  end

  scenario 'values from submodule-scoped includes are merged with include-order precedence' do
    find('tr', text: 'Include Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    # m1.yml (main): A=m1a B=m1b C=m1c; then m2.yml from submodule "submodule": C=m2c;
    # then data/m3.yml from submodule "submodule-data-only": B=m3b  => A=m1a B=m3b C=m2c
    env = task_env('include-demo', 'submodule-include-precedence')
    expect(env).not_to be_nil
    expect([env[:a], env[:b], env[:c]]).to eq %w[m1a m3b m2c]
  end

  scenario 'plain and nested (non-submodule) includes still resolve in the parent commit' do
    find('tr', text: 'Include Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    env = task_env('include-demo', 'nested-toplevel-includes')
    expect(env).not_to be_nil
    expect([env[:a], env[:b], env[:c]]).to eq %w[local-value m1b m2c]
  end
end
