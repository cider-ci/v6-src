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
  end
end