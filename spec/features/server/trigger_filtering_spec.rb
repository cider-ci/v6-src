require 'spec_helper'
require 'timeout'
require 'securerandom'

feature 'Trigger filtering' do

  let(:project_id) { "trigger-filter-#{SecureRandom.hex(4)}" }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../fixtures/trigger-filter-repo.git').realdirpath }
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

  scenario 'trigger.branch.include_match filters which branches fire a job' do
    setup_repo
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click

    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 2 }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 3 }

    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 2
    expect(database[:jobs].where(project_id: project_id, key: 'master-only').count).to eq 1
  end

  scenario 'repo branch_trigger_include_match suppresses jobs on non-matching branches' do
    setup_repo(branch_trigger_include_match: '^master$')
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click

    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 2 }
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 2 }
    sleep 3  # give feature-branch trigger a full window to (wrongly) fire

    # master gets all-branches + master-only (2); feature branch must be suppressed
    expect(database[:jobs].where(project_id: project_id).count).to eq 2
    expect(database[:jobs].where(project_id: project_id, key: 'all-branches').count).to eq 1
    expect(database[:jobs].where(project_id: project_id, key: 'master-only').count).to eq 1
  end

end
