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

    [{id: @commit_a}, {id: @commit_b}].each do |c|
      database[:commits].insert(
        id: c[:id], tree_id: c[:id].tr('a-z', 'b-z0'),
        subject: 'test', author_name: 'A', committer_name: 'A'
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
  end

  scenario 'filter by state shows only matching jobs' do
    visit '/jobs/'
    find('select').select('failed')
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

end
