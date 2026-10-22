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

    [{id: @commit_a1, subject: 'Commit A1 on master',
      author_name: 'Ada Lovelace', author_email: 'ada@example.com'},
     {id: @commit_a2, subject: 'Commit A2 on feature',
      author_name: 'Grace Hopper', author_email: 'grace@example.com'},
     {id: @commit_b1, subject: 'Commit B1 on main',
      author_name: 'Admin Self', author_email: @admin.email_address.upcase}].each do |c|
      database[:commits].insert(
        id: c[:id], tree_id: c[:id].tr('a-z', 'b-z0'), subject: c[:subject],
        author_name: c[:author_name], author_email: c[:author_email],
        committer_name: 'Committer', committer_email: 'committer@example.com',
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
    # one passed job -> a green count badge in the CI column, linking to the jobs page
    expect(page).to have_css 'td.jobs-summary a.badge.bg-success', text: '1'
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

  scenario 'clear button resets submitted filter fields' do
    visit '/commits/'
    fill_in placeholder: 'name or email', with: 'grace'
    find('button', text: 'Filter').click
    expect(page).not_to have_text 'Commit A1 on master'

    find('button', text: 'Clear').click
    expect(page).to have_text 'Commit A1 on master'
    expect(page).to have_field placeholder: 'name or email', with: ''
  end

  scenario 'clear button removes filters' do
    visit '/commits/?project=' + repo_b_id
    expect(page).to have_text 'Commit B1 on main'
    expect(page).not_to have_text 'Commit A1'

    find('button', text: 'Clear').click
    expect(page).to have_text 'Commit A1 on master'
    expect(page).to have_text 'Commit B1 on main'
  end

  scenario 'filter by author regex matches name or email, case-insensitively' do
    visit '/commits/'
    fill_in placeholder: 'name or email', with: 'grace'
    find('button', text: 'Filter').click

    expect(page).to have_text 'Commit A2 on feature'
    expect(page).not_to have_text 'Commit A1 on master'
    expect(page).not_to have_text 'Commit B1 on main'

    fill_in placeholder: 'name or email', with: 'ADA@example'
    find('button', text: 'Filter').click
    expect(page).to have_text 'Commit A1 on master'
    expect(page).not_to have_text 'Commit A2 on feature'
  end

  scenario 'the filter icon in the author cell filters by that author, keeping other filters' do
    visit '/commits/?branch=master|feature'
    expect(page).to have_text 'Commit A2 on feature'

    within 'tr', text: 'Commit A1 on master' do
      find('td.author a[title="Filter by author Ada Lovelace"]').click
    end

    expect(page).to have_text 'Commit A1 on master'
    expect(page).not_to have_text 'Commit A2 on feature'
    expect(page).to have_field placeholder: 'name or email', with: '^Ada Lovelace$'
    expect(page).to have_field placeholder: '^master$', with: 'master|feature'
  end

  scenario 'my commits shows commits matching one of my email addresses' do
    visit '/commits/'
    check 'My commits'
    find('button', text: 'Filter').click

    expect(page).to have_text 'Commit B1 on main'
    expect(page).not_to have_text 'Commit A1 on master'
    expect(page).not_to have_text 'Commit A2 on feature'
    expect(page).to have_current_path(/mine=true/)

    find('button', text: 'Clear').click
    expect(page).to have_text 'Commit A1 on master'
    expect(page).to have_unchecked_field 'My commits'
  end

end
