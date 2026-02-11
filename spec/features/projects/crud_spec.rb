require 'git'
require 'fileutils'
require 'spec_helper'


feature 'Projects' do

  context 'As an admin user' do
    include_context :signed_in_as_an_admin

    scenario 'something' do
      visit '/'
      click_on 'Projects'
      click_on 'Create'
      fill_in 'id', with: 'cider-ci-demo-project'
      fill_in 'name', with: 'Cider-CI Demo-Project'
      fill_in 'url', with: 'https://github.com/cider-ci/cider-ci_demo-project-bash.git'
      binding.pry
      click_on 'Create'
      wait_until (30) do
        within(tr_project('cider-ci-demo-project')) do
          all("td.fetch.success").first
        end
      end
    end
  end
end