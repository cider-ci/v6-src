require 'spec_helper'
require 'timeout'

feature 'Trigger filtering' do

  let(:project_id) { 'trigger-filter-test' }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../fixtures/trigger-filter-repo.git').realdirpath }
  let(:git_url)     { "file://#{fixture_dir}" }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      project_id,
      name:    'Trigger Filter Test',
      git_url: git_url
    )

    # Force the server's in-memory repository state to include the new repo.
    # (The 1s daemon cycle can race with the TRUNCATE+INSERT sequence.)
    reload_server_repository_state
    sleep 1
  end

  scenario 'trigger.branch.include_match filters which branches fire a job' do
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click

    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 2 }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 3 }

    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 2
    expect(database[:jobs].where(project_id: project_id, key: 'master-only').count).to eq 1
  end

  scenario 'repo branch_trigger_include_match suppresses jobs on non-matching branches' do
    database[:repositories].where(id: project_id)
      .update(branch_trigger_include_match: '^master$')
    reload_server_repository_state

    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click

    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 2 }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 2 }
    sleep 2  # give feature-branch a chance to (wrongly) fire

    # master gets all-branches + master-only (2); feature gets nothing
    expect(database[:jobs].where(project_id: project_id).count).to eq 2
    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 1
    expect(database[:jobs].where(project_id: project_id, key: 'master-only').count).to eq 1
  end

end
