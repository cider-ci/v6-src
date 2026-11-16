require 'spec_helper'
require 'securerandom'

feature 'Jobs Dashboard' do

  let(:repo_a_id) { 'jobs-dashboard-repo-a' }
  let(:repo_b_id) { 'jobs-dashboard-repo-b' }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(id: repo_a_id, name: 'Repo A', git_url: "local://#{repo_a_id}")
    database[:repositories].insert(id: repo_b_id, name: 'Repo B', git_url: "local://#{repo_b_id}")

    @commit_a = 'a' * 40
    @commit_b = 'b' * 40

    [{id: @commit_a, committer_name: 'Ada Lovelace', committer_email: 'ada@example.com'},
     {id: @commit_b, committer_name: 'Grace Hopper', committer_email: 'grace@example.com'}].each do |c|
      database[:commits].insert(
        id: c[:id], tree_id: c[:id].tr('a-z', 'b-z0'), subject: "subject of #{c[:id][0, 4]}",
        author_name: 'Ada Lovelace', author_email: 'ada@example.com',
        committer_name: c[:committer_name], committer_email: c[:committer_email]
      )
    end

    @job_a = SecureRandom.uuid
    @job_b = SecureRandom.uuid

    database[:jobs].insert(id: @job_a, project_id: repo_a_id, commit_id: @commit_a,
                            key: 'build', name: 'Build', state: 'passed')
    database[:jobs].insert(id: @job_b, project_id: repo_b_id, commit_id: @commit_b,
                            key: 'test',  name: 'Test',  state: 'failed')
  end

  scenario 'shows all jobs by default' do
    visit '/jobs/'
    expect(page).to have_content 'Jobs'
    expect(page).to have_css '.badge', text: 'passed'
    expect(page).to have_css '.badge', text: 'failed'
    expect(page).to have_content 'Repo A'
    expect(page).to have_content 'Repo B'

    # who: the author's avatar, plus the committer's when that is someone
    # else; the commit link carries the subject
    row_a = find('tr', text: 'Build')
    expect(row_a).to have_css 'img.avatar[alt="Ada Lovelace"]', count: 1
    expect(row_a).to have_css "a[title='subject of aaaa']", text: @commit_a[0, 7]
    row_b = find('tr', text: 'Test')
    expect(row_b).to have_css 'img.avatar[alt="Ada Lovelace"]'
    expect(row_b).to have_css 'img.avatar[alt="Grace Hopper"]'
    row_b.find('img.avatar[alt="Grace Hopper"]').hover
    expect(page).to have_css '.tooltip', text: /Grace Hopper.*grace@example.com.*committer/m
  end

  scenario 'filter by state shows only matching jobs' do
    visit '/jobs/'
    find('#state-filter').find(:option, 'failed').select_option
    find('button', text: 'Filter').click

    expect(page).to have_css '.badge', text: 'failed'
    expect(page).not_to have_css '.badge', text: 'passed'
    expect(page).not_to have_content 'Repo A'
  end

  scenario 'filter by project-id shows only jobs from that project' do
    visit '/jobs/'
    fill_in placeholder: 'project-id', with: repo_a_id
    find('button', text: 'Filter').click

    expect(page).to have_content 'Repo A'
    expect(page).not_to have_content 'Repo B'
    expect(page).to have_css '.badge', text: 'passed'
    expect(page).not_to have_css '.badge', text: 'failed'
  end

  scenario 'clicking a job navigates to the job detail page' do
    visit '/jobs/'
    find('a code', text: 'build').click
    expect(page).to have_current_path(
      "/projects/#{repo_a_id}/commits/#{@commit_a}/jobs/#{@job_a}", ignore_query: true
    )
  end

  scenario 'clear button removes filters' do
    visit "/jobs/?state=failed"
    expect(page).to have_css '.badge', text: 'failed'
    expect(page).not_to have_css '.badge', text: 'passed'

    find('button', text: 'Clear').click
    expect(page).to have_css '.badge', text: 'passed'
    expect(page).to have_css '.badge', text: 'failed'
  end

  scenario 'shows empty message when no jobs match' do
    visit "/jobs/?state=aborted"
    expect(page).to have_content 'No jobs found.'
  end

  scenario 'filter icon next to project name sets project filter' do
    visit '/jobs/'
    within('tr', text: 'Test') do
      find('button[title="Filter by this project"]').click
    end
    expect(page).to have_content 'Repo B'
    expect(page).not_to have_content 'Repo A'
  end

  scenario 'sort dropdown changes URL to sort by latest trial' do
    visit '/jobs/'
    find('#sort-filter').find(:option, 'By latest trial').select_option
    find('button', text: 'Filter').click
    expect(page.current_url).to include('sort=trial')
    expect(page).to have_css '.badge', text: 'passed'
    expect(page).to have_css '.badge', text: 'failed'
  end

  scenario 'reload button is present' do
    visit '/jobs/'
    expect(page).to have_css('button[title="Reload"]')
  end

end
