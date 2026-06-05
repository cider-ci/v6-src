require 'spec_helper'
require 'securerandom'

feature 'Commits Dashboard' do

  let(:repo_id)   { 'dashboard-test-repo' }
  let(:commit_id) { 'a' * 40 }
  let(:tree_id)   { 'b' * 40 }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      repo_id,
      name:    'Dashboard Test Repo',
      git_url: 'local'
    )

    database[:commits].insert(
      id:             commit_id,
      tree_id:        tree_id,
      subject:        'Test commit for dashboard',
      author_name:    'Test Author',
      committer_name: 'Test Committer',
      committer_date: Time.now.utc
    )

    @branch_id = SecureRandom.uuid
    database[:branches].insert(
      id:                @branch_id,
      repository_id:     repo_id,
      name:              'main',
      current_commit_id: commit_id
    )

    database[:jobs].insert(
      project_id: repo_id,
      commit_id:  commit_id,
      key:        'test-job',
      name:       'Test Job',
      state:      'passed'
    )
  end

  scenario 'shows branch-head commits with branch and job info' do
    visit '/commits/'

    expect(page).to have_text 'Test commit for dashboard'
    expect(page).to have_text 'main'
    expect(page).to have_css '.badge.bg-success', text: 'test-job'
  end

end
