require 'git'
require 'fileutils'
require 'spec_helper'


feature 'Projects' do

  context 'As an admin user' do
    include_context :signed_in_as_an_admin

    scenario 'can create a project' do
      visit '/'
      click_on 'Projects'
      click_on 'New Project'
      fill_in 'id', with: 'cider-ci-demo-project'
      fill_in 'name', with: 'Cider-CI Demo-Project'
      fill_in 'git_url', with: 'https://github.com/cider-ci/cider-ci_demo-project-bash.git'
      click_on 'Create'
      expect(page).to have_current_path('/projects/cider-ci-demo-project', ignore_query: true)
      visit '/projects/'
      wait_until(10) { tr_project('cider-ci-demo-project') }
    end

    scenario 'lists projects ordered by id' do
      # inserted out of order; without ORDER BY the list followed the heap order
      %w[zeta-project alpha-project mid-project].each do |id|
        database[:repositories].insert(id: id, name: id, git_url: "file:///nowhere/#{id}.git",
                                       branch_trigger_include_match: '^__none__$')
      end
      visit '/projects/'
      wait_until(10) { tr_project('zeta-project') }
      expect(all('tr.project td.id').map(&:text)).to eq %w[alpha-project mid-project zeta-project]
    end
  end
end