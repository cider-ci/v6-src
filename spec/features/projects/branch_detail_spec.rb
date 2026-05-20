require 'spec_helper'

feature 'Branch detail page' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @project_id = 'demo-repo'
    database[:repositories].insert(
      id: @project_id,
      name: 'Demo Repository',
      git_url: 'https://example.test/demo.git'
    )
  end

  def insert_commit(id, subject = 'A commit')
    database[:commits].insert(
      id: id,
      tree_id: 'b' * 40,
      depth: 0,
      author_name: 'Alice',
      author_email: 'alice@example.test',
      author_date:  Time.now,
      committer_name: 'Alice',
      committer_email: 'alice@example.test',
      committer_date:  Time.now,
      subject: subject,
      body:    ''
    )
  end

  def insert_branch(name, current_commit_id)
    database[:branches].insert(
      repository_id: @project_id,
      name: name,
      current_commit_id: current_commit_id
    ).tap do |_|
      # find the inserted branch id (Sequel returns it but PG with text PK may not)
      database[:branches].where(repository_id: @project_id, name: name).first[:id]
    end
  end

  scenario 'renders branch header and recent commits' do
    cid1 = '1' * 40
    cid2 = '2' * 40
    insert_commit(cid1, 'First commit')
    insert_commit(cid2, 'Second commit')
    branch_id = database[:branches].insert(
      repository_id: @project_id, name: 'main', current_commit_id: cid2
    )
    branch_id = database[:branches].where(repository_id: @project_id, name: 'main').first[:id]
    database[:branches_commits].insert(branch_id: branch_id, commit_id: cid1)
    database[:branches_commits].insert(branch_id: branch_id, commit_id: cid2)

    visit "/projects/#{@project_id}/branches/main"

    expect(page).to have_content 'main'
    expect(page).to have_content 'Current commit'
    expect(page).to have_content cid2[0, 8]
    within('table.commits') do
      expect(page).to have_content 'First commit'
      expect(page).to have_content 'Second commit'
      expect(page).to have_content cid1[0, 8]
      expect(page).to have_content cid2[0, 8]
      expect(page).to have_content 'Alice'
    end
  end

  scenario 'empty branch shows empty state' do
    cid = 'a' * 40
    insert_commit(cid)
    database[:branches].insert(
      repository_id: @project_id, name: 'empty-branch', current_commit_id: cid
    )

    visit "/projects/#{@project_id}/branches/empty-branch"
    expect(page).to have_content 'empty-branch'
    expect(page).to have_content 'No commits in this branch'
  end

  scenario 'navigates from project detail to branch detail' do
    cid = '5' * 40
    insert_commit(cid)
    database[:branches].insert(
      repository_id: @project_id, name: 'main', current_commit_id: cid
    )

    visit "/projects/#{@project_id}"
    click_on 'main'

    expect(current_path).to eq "/projects/#{@project_id}/branches/main"
    expect(page).to have_content 'main'
  end

  scenario 'unknown branch returns 404' do
    visit "/projects/#{@project_id}/branches/does-not-exist"
    expect(page).to have_content 'Request ERROR 404'
  end

  scenario 'handles branch names with slashes (catch-all wildcard)' do
    cid = '7' * 40
    insert_commit(cid, 'Feature commit')
    branch_name = 'feature/login'
    database[:branches].insert(
      repository_id: @project_id, name: branch_name, current_commit_id: cid
    )
    branch_id = database[:branches]
                  .where(repository_id: @project_id, name: branch_name).first[:id]
    database[:branches_commits].insert(branch_id: branch_id, commit_id: cid)

    visit "/projects/#{@project_id}/branches/#{branch_name}"
    expect(page).to have_content branch_name
    expect(page).to have_content 'Feature commit'
  end

end
