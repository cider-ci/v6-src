require 'spec_helper'

feature 'Project detail page' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @project_id = 'demo-repo'
    database[:repositories].insert(
      id: @project_id,
      name: 'Demo Repository',
      git_url: 'https://example.test/demo.git'
    )

    @commit_id = 'a' * 40
    database[:commits].insert(
      id: @commit_id,
      tree_id: 'b' * 40,
      depth: 0,
      author_name: 'Alice',
      author_email: 'alice@example.test',
      committer_name: 'Alice',
      committer_email: 'alice@example.test',
      subject: 'Initial commit',
      body: ''
    )

    database[:branches].insert(
      repository_id: @project_id,
      name: 'main',
      current_commit_id: @commit_id
    )
  end

  scenario 'navigates from list to detail and renders project + branches' do
    visit '/projects/'
    click_on 'Demo Repository'

    expect(current_path).to eq "/projects/#{@project_id}"

    expect(page).to have_content 'Demo Repository'
    expect(page).to have_content 'https://example.test/demo.git'
    expect(page).to have_content @project_id

    within('table.branches') do
      expect(page).to have_content 'main'
      expect(page).to have_content 'Initial commit'
      expect(page).to have_content @commit_id[0, 8]
    end
  end

  scenario 'unknown project id returns 404' do
    visit '/projects/does-not-exist'
    expect(page).to have_content 'Request ERROR 404'
  end

  scenario 'shows empty state when project has no branches' do
    database[:branches].where(repository_id: @project_id).delete
    visit "/projects/#{@project_id}"
    expect(page).to have_content 'No branches yet'
  end

end
