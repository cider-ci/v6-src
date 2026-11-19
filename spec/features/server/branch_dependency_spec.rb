require 'spec_helper'
require 'timeout'
require 'securerandom'

# depends_on type branch (legacy parity): the job can run where the head of a
# branch matching include_match has the job's commit's tree. Typical use: a
# "merged-to-master" job that is triggered when master moves, and that one
# can start by hand later (e.g. after the auto-trigger's commit age passed),
# but never on a commit that is not master's head.
feature 'Branch dependency' do

  let(:project_id)  { "branch-dep-#{SecureRandom.hex(4)}" }
  let(:fixture_dir) { Pathname.new(__FILE__).join('../../../fixtures/trigger-filter-repo.git').realdirpath }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: project_id, name: 'Branch Dependency Test',
                                   git_url: "file://#{fixture_dir}",
                                   branch_trigger_include_match: '^__none__$')  # no auto-triggers
    reload_server_repository_state
    sleep 1
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 3 }
  end

  def head(branch)
    database[:branches].where(repository_id: project_id, name: branch).first[:current_commit_id]
  end

  scenario "runnable on master's head, waiting elsewhere" do
    visit "/projects/#{project_id}/commits/#{head('feature')}/jobs"
    row = find('tr', text: 'On Master Job')
    expect(row).to have_content 'Waiting for: master branch matches'
    expect(row).not_to have_button 'Run'

    visit "/projects/#{project_id}/commits/#{head('master')}/jobs"
    row = find('tr', text: 'On Master Job')
    expect(row).not_to have_content 'Waiting for'
    row.find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'
    expect(database[:jobs].where(project_id: project_id, key: 'on-master', commit_id: head('master')).count).to eq 1
  end
end
