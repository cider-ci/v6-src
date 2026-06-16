require 'spec_helper'
require 'securerandom'

feature 'Commits Dashboard' do

  let(:repo_a_id) { 'dashboard-repo-a' }
  let(:repo_b_id) { 'dashboard-repo-b' }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(id: repo_a_id, name: 'Repo A', git_url: "local://#{repo_a_id}")
    database[:repositories].insert(id: repo_b_id, name: 'Repo B', git_url: "local://#{repo_b_id}")

    # Two commits on repo A (master + feature) and one on repo B (main)
    @commit_a1 = 'a' * 40
    @commit_a2 = 'b' * 40
    @commit_b1 = 'c' * 40

    [{id: @commit_a1, subject: 'Commit A1 on master'},
     {id: @commit_a2, subject: 'Commit A2 on feature'},
     {id: @commit_b1, subject: 'Commit B1 on main'}].each do |c|
      database[:commits].insert(
        id: c[:id], tree_id: c[:id].tr('a-z', 'b-z0'), subject: c[:subject],
        author_name: 'Author', committer_name: 'Committer',
        committer_date: Time.now.utc
      )
    end

    database[:branches].insert(id: SecureRandom.uuid, repository_id: repo_a_id,
                                name: 'master',  current_commit_id: @commit_a1)
    database[:branches].insert(id: SecureRandom.uuid, repository_id: repo_a_id,
                                name: 'feature', current_commit_id: @commit_a2)
    database[:branches].insert(id: SecureRandom.uuid, repository_id: repo_b_id,
                                name: 'main',    current_commit_id: @commit_b1)

    database[:jobs].insert(project_id: repo_a_id, commit_id: @commit_a1,
                            key: 'ci', name: 'CI', state: 'passed')
  end

  scenario 'shows all branch-head commits by default' do
    visit '/commits/'
    expect(page).to have_text 'Commit A1 on master'
    expect(page).to have_text 'Commit A2 on feature'
    expect(page).to have_text 'Commit B1 on main'
    expect(page).to have_css '.badge.bg-success', text: 'ci'
  end

  scenario 'filter by project shows only commits from that project' do
    visit '/commits/'
    fill_in placeholder: 'project-id', with: repo_b_id
    find('button', text: 'Filter').click

    expect(page).to have_text 'Commit B1 on main'
    expect(page).not_to have_text 'Commit A1'
    expect(page).not_to have_text 'Commit A2'
  end

  scenario 'filter by branch regex shows only matching branches' do
    visit '/commits/'
    fill_in placeholder: '^master$', with: 'master'
    find('button', text: 'Filter').click

    expect(page).to have_text 'Commit A1 on master'
    expect(page).not_to have_text 'Commit A2 on feature'
    expect(page).not_to have_text 'Commit B1 on main'
  end

  scenario 'clear button removes filters' do
    visit '/commits/?project=' + repo_b_id
    expect(page).to have_text 'Commit B1 on main'
    expect(page).not_to have_text 'Commit A1'

    find('button', text: 'Clear').click
    expect(page).to have_text 'Commit A1 on master'
    expect(page).to have_text 'Commit B1 on main'
  end

end
