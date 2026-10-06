require 'spec_helper'
require 'timeout'

# depends_on entries qualified with `submodule: [...]` (legacy semantics):
#
#   job-submodule-dependent:
#     depends_on:
#       job-prerequisite_in_submodule_has_passed:
#         type: job
#         job_key: job-prerequisite
#         submodule: ['submodule']
#         states: [passed]
#
# is satisfied by a job `job-prerequisite` on the commit the parent's gitlink
# `submodule` points to (d841bd0 for the demo HEAD), in whichever project that
# commit was tested. leihs' good-to-merge jobs rely on this ("database merged
# to master"). Previously v6 treated every submodule dependency as unmet.
feature 'Submodule job dependencies' do

  SUBDEP_PROJECT_ID  = 'cider-ci-demo-project'
  SUBDEP_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'
  SUBDEP_SUB_COMMIT  = 'd841bd004ba820c4f8d4568b5198904a7ec6c708' # gitlink "submodule"

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: SUBDEP_PROJECT_ID, name: 'Demo Project', git_url: 'local',
                                   branch_trigger_include_match: '^__none__$') # no auto-triggered demo jobs
    [SUBDEP_HEAD_COMMIT, SUBDEP_SUB_COMMIT].each do |c|
      database[:commits].insert(id: c, tree_id: 'f' * 40, author_name: 'A', committer_name: 'A', subject: 'fixture')
    end
  end

  def insert_job!(commit_id, key, state)
    database[:jobs].insert(id: SecureRandom.uuid, project_id: SUBDEP_PROJECT_ID, commit_id: commit_id,
                           key: key, name: key, state: state)
  end

  def submodule_dependent_row
    find('tr', text: 'Jobs - Dependencies and Triggers - Submodule-Dependent')
  end

  scenario 'is unmet without a passed job on the submodule commit' do
    visit "/projects/#{SUBDEP_PROJECT_ID}/commits/#{SUBDEP_HEAD_COMMIT}/jobs"
    expect(submodule_dependent_row).not_to have_button('Run')
    expect(submodule_dependent_row).to have_content('Waiting for: job-prerequisite_in_submodule_has_passed')
  end

  scenario 'is met by a passed job on the submodule commit and the dep-trigger creates the job' do
    insert_job!(SUBDEP_SUB_COMMIT, 'job-prerequisite', 'passed')
    # a job on the parent commit makes it "recently active" for the dep-trigger
    insert_job!(SUBDEP_HEAD_COMMIT, 'info', 'passed')

    visit "/projects/#{SUBDEP_PROJECT_ID}/commits/#{SUBDEP_HEAD_COMMIT}/jobs"
    # either still runnable (Run button) or already auto-created by the dep-trigger
    Timeout.timeout(40) do
      sleep 1 until database[:jobs].where(project_id: SUBDEP_PROJECT_ID, commit_id: SUBDEP_HEAD_COMMIT,
                                          key: 'job-submodule-dependent').count == 1
    end
    visit "/projects/#{SUBDEP_PROJECT_ID}/commits/#{SUBDEP_HEAD_COMMIT}/jobs"
    expect(page).to have_content 'Recorded Jobs'
    expect(page).to have_css('tr', text: 'job-submodule-dependent')
    expect(page).not_to have_content('Waiting for: job-prerequisite_in_submodule_has_passed')
  end
end
