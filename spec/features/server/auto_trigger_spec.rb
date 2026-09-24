require 'spec_helper'
require 'timeout'
require 'securerandom'

feature 'Auto-trigger' do

  # The trigger-filter fixture has jobs with `run_when: {type: branch}`; the
  # demo project's jobs have none and are therefore never auto-triggered by a
  # branch update (legacy semantics).
  let(:project_id)  { "auto-trigger-#{SecureRandom.hex(4)}" }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../../fixtures/trigger-filter-repo.git').realdirpath }
  let(:git_url)     { "file://#{fixture_dir}" }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:                            project_id,
      name:                          'Auto Trigger Fixture',
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
    expect(database[:jobs].where(project_id: project_id, key: 'no-trigger').count).to eq 0

    job = database[:jobs].where(project_id: project_id).first
    expect(job[:state]).to eq 'pending'
    expect(database[:tasks].where(job_id: job[:id]).count).to be > 0
    expect(database[:trials].count).to be > 0
  end

end
