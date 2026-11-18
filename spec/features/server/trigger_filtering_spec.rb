require 'spec_helper'
require 'timeout'
require 'securerandom'

feature 'Trigger filtering' do

  let(:project_id) { "trigger-filter-#{SecureRandom.hex(4)}" }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../../fixtures/trigger-filter-repo.git').realdirpath }
  let(:git_url)     { "file://#{fixture_dir}" }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
  end

  def setup_repo(extra = {})
    database[:repositories].insert({
      id:                            project_id,
      name:                          'Trigger Filter Test',
      git_url:                       git_url,
      branch_trigger_max_commit_age: nil
    }.merge(extra))
    reload_server_repository_state
    sleep 1
  end

  scenario 'run_when branch include_match filters which branches fire a job' do
    setup_repo
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click

    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 3 }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 3 }
    sleep 3

    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 2   # not feature-NO_CI
    expect(database[:jobs].where(project_id: project_id, key: 'master-only').count).to eq 1
    # legacy semantics: a job without a run_when branch entry is never
    # auto-triggered by a push (leihs' all-broken-scenarios must stay manual)
    expect(database[:jobs].where(project_id: project_id, key: 'no-trigger').count).to eq 0
  end

  # The fixture has three branches: master, feature and feature-NO_CI (its own
  # commit). The repository filter applies to automatic triggers only.
  NO_CI_COMMIT = '6fdfe4b3684bad53a1b396f6e7a6b7ec0a30423c'

  def fetch_and_wait(branches:, jobs:)
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= branches }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= jobs } if jobs > 0
    sleep 3  # a full window for anything that would (wrongly) fire
  end

  def jobs_on(commit_id)
    database[:jobs].where(project_id: project_id, commit_id: commit_id)
  end

  scenario 'the default exclude NO_CI keeps a NO_CI branch from auto-triggering; by hand it still runs' do
    setup_repo   # defaults: include ^.+$, exclude NO_CI
    expect(database[:repositories].where(id: project_id).first[:branch_trigger_exclude_match]).to eq 'NO_CI'
    fetch_and_wait(branches: 3, jobs: 3)

    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 2   # master, feature
    expect(jobs_on(NO_CI_COMMIT).count).to eq 0

    visit "/projects/#{project_id}/commits/#{NO_CI_COMMIT}/jobs"
    find('tr', text: 'All Branches Job').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'
    expect(jobs_on(NO_CI_COMMIT).where(key: 'all-branches').count).to eq 1
  end

  scenario 'a NULL exclude disables the check: the NO_CI branch is tested like any other' do
    setup_repo(branch_trigger_exclude_match: nil)
    fetch_and_wait(branches: 3, jobs: 4)
    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 3
    expect(jobs_on(NO_CI_COMMIT).count).to eq 1
  end

  scenario 'the exclude overrides the include' do
    setup_repo(branch_trigger_include_match: '^feature', branch_trigger_exclude_match: 'NO_CI')
    fetch_and_wait(branches: 3, jobs: 1)
    # feature matches both: excluded wins for feature-NO_CI, master fails the include
    expect(database[:jobs].where(project_id: project_id).map { |j| [j[:key], j[:commit_id]] })
      .to eq [['all-branches', database[:branches].where(repository_id: project_id, name: 'feature').first[:current_commit_id]]]
  end

  scenario 'the all matcher ^.*$ as exclude auto-triggers no branch at all' do
    setup_repo(branch_trigger_include_match: '^.+$', branch_trigger_exclude_match: '^.*$')
    fetch_and_wait(branches: 3, jobs: 0)
    expect(database[:jobs].where(project_id: project_id).count).to eq 0
  end

  scenario 'the project form: a blank exclude disables the check, a blank include is every branch' do
    setup_repo
    visit "/projects/#{project_id}/edit"
    expect(page).to have_field 'branch_trigger_include_match', with: '^.+$'
    expect(page).to have_field 'branch_trigger_exclude_match', with: 'NO_CI'
    fill_in 'branch_trigger_include_match', with: ''
    fill_in 'branch_trigger_exclude_match', with: ''
    click_on 'Save'
    expect(page).to have_css '.branch-trigger-filter', text: 'matching ^.+$ (no exclusion)'
    repo = database[:repositories].where(id: project_id).first
    expect(repo[:branch_trigger_include_match]).to eq '^.+$'
    expect(repo[:branch_trigger_exclude_match]).to be_nil

    visit "/projects/#{project_id}/edit"
    fill_in 'branch_trigger_exclude_match', with: 'wip'
    click_on 'Save'
    expect(page).to have_css '.branch-trigger-filter', text: 'matching ^.+$, except wip'
  end

  scenario 'repo branch_trigger_include_match suppresses jobs on non-matching branches' do
    setup_repo(branch_trigger_include_match: '^master$')
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click

    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 3 }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 2 }
    sleep 3  # give feature-branch trigger a full window to (wrongly) fire

    # master gets all-branches + master-only (2); feature branch must be suppressed
    expect(database[:jobs].where(project_id: project_id).count).to eq 2
    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 1
    expect(database[:jobs].where(project_id: project_id, key: 'master-only').count).to eq 1
  end

end
