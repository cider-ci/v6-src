require 'spec_helper'

# The tree nav (legacy parity): every page shows its position in the tree,
# derived from the address, each item a link.
feature 'Breadcrumbs' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    @project_id = 'crumb-project'
    @commit_id  = 'a' * 40
    database[:repositories].insert(id: @project_id, name: 'Crumb', git_url: 'https://example.test/crumb.git')
    database[:commits].insert(id: @commit_id, tree_id: 'b' * 40, subject: 'Crumb commit',
                              author_name: 'A', committer_name: 'A', committer_date: Time.now.utc)
    @job_id = database[:jobs].insert(project_id: @project_id, commit_id: @commit_id, key: 'crumb-job',
                                     name: 'Crumb Job', state: 'passed')
  end

  def crumbs
    all('ol.breadcrumb li').map(&:text)
  end

  scenario 'project, commit, jobs and job pages' do
    visit '/projects/'
    expect(crumbs).to eq ['Projects']

    visit "/projects/#{@project_id}"
    expect(crumbs).to eq ['Projects', @project_id]
    expect(find('ol.breadcrumb a', text: 'Projects')[:href]).to end_with '/projects/'

    visit "/projects/#{@project_id}/commits/#{@commit_id}"
    expect(crumbs).to eq ['Projects', @project_id, @commit_id[0, 8]]

    visit "/projects/#{@project_id}/commits/#{@commit_id}/jobs/#{@job_id}"
    expect(page).to have_css 'h3', text: 'Crumb Job'
    expect(crumbs).to eq ['Projects', @project_id, @commit_id[0, 8], 'Jobs', 'crumb-job']   # the job key, not the id
    find('ol.breadcrumb a', text: 'Jobs').click
    expect(crumbs).to eq ['Projects', @project_id, @commit_id[0, 8], 'Jobs']
  end

  scenario 'the global pages and the admin pages' do
    visit '/executors/'
    expect(crumbs).to eq ['Executors']
    visit '/admin/settings/edit'
    expect(crumbs).to eq ['Admin', 'Settings', 'Edit']
    visit '/scheduled/'
    expect(crumbs).to eq ['Scheduled']
    visit '/sign-in'
    expect(page).not_to have_css 'ol.breadcrumb'
  end
end
