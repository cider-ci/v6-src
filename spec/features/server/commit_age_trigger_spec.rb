require 'spec_helper'
require 'timeout'
require 'securerandom'

feature 'Commit age trigger filtering' do

  let(:project_id) { "commit-age-#{SecureRandom.hex(4)}" }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../fixtures/trigger-filter-repo.git').realdirpath }
  let(:git_url)     { "file://#{fixture_dir}" }

  def do_fetch
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 2 }
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
  end

  scenario 'commits older than max_commit_age are not triggered' do
    database[:repositories].insert(
      id:                            project_id,
      name:                          'Commit Age Test',
      git_url:                       git_url,
      branch_trigger_max_commit_age: '1 second'
    )
    reload_server_repository_state
    sleep 1

    do_fetch
    sleep 3  # give trigger a full window to (wrongly) fire

    expect(database[:jobs].where(project_id: project_id).count).to eq 0
  end

  scenario 'commits within max_commit_age are triggered normally' do
    database[:repositories].insert(
      id:                            project_id,
      name:                          'Commit Age Test',
      git_url:                       git_url,
      branch_trigger_max_commit_age: nil
    )
    reload_server_repository_state
    sleep 1

    do_fetch
    Timeout.timeout(30) { sleep 1 until database[:jobs].where(project_id: project_id).count >= 3 }

    expect(database[:jobs].where(project_id: project_id).count).to be >= 3
  end

end
