require 'spec_helper'
require 'timeout'

feature 'Auto-trigger' do

  let(:project_id)  { 'cider-ci-demo-project' }
  let(:project_root){ Pathname.new(__FILE__).join('../../..').realdirpath }
  let(:git_url)     { "file://#{project_root}/data/repositories/#{project_id}" }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:                            project_id,
      name:                          'Demo Project',
      git_url:                       git_url,
      branch_trigger_max_commit_age: nil
    )

    # Force the server's in-memory repository state to include the new repo
    reload_server_repository_state
    sleep 1
  end

  scenario 'branch update auto-triggers jobs from cider-ci.yml' do
    visit "/projects/#{project_id}"
    find('button', text: /[Ff]etch/).click

    # Wait for the git fetch and branch update to complete
    Timeout.timeout(30) do
      sleep 1 until database[:branches].where(repository_id: project_id).count > 0
    end

    # Wait for the auto-trigger to create jobs
    Timeout.timeout(30) do
      sleep 1 until database[:jobs].where(project_id: project_id).count > 0
    end

    expect(database[:jobs].where(project_id: project_id).count).to be > 0

    job = database[:jobs].where(project_id: project_id).first
    expect(job[:state]).to eq 'pending'
    expect(database[:tasks].where(job_id: job[:id]).count).to be > 0
    expect(database[:trials].count).to be > 0
  end

end
