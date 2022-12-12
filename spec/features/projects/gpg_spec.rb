require 'git'
require 'fileutils'
require 'spec_helper'

def tr_project(id)
  find("tr.project td.id", text: id).ancestor("tr")
end



feature 'Projects' do

  context 'As an admin user' do
    before :each do
      @admin ||= FactoryBot.create :admin, :with_gpg_key
      @current_user = @admin
    end
    include_context :signed_in_as_current_user

    context 'Repo with sigend commits' do

      scenario 'parsing a 3 commit git repo' do

        visit '/'

        ENV['GNUPGHOME']=@current_user.gpg_home.to_s

        test_repo_path = PROJECT_DIR.join("tmp").join("test-repo")
        FileUtils.rm_rf(test_repo_path)
        test_repo = Git.init(test_repo_path)
        test_repo.config('user.name', @current_user.name)
        test_repo.config('user.email', @current_user.email_address)

        (1..3).each do |i|
          File.write(test_repo_path.join("README.txt"), "Commit #{'%02d' % [i]}")
          test_repo.add("README.txt")
          test_repo.commit("Commit #{'%02d' % [i]}", no_gpg_sign: false)
        end

        click_on 'Projects'
        click_on 'Create'
        fill_in 'id', with: 'test-repo'
        fill_in 'name', with: 'Test Repo'
        fill_in 'url', with: test_repo_path
        click_on 'Create'
        wait_until (30) do
          within(tr_project('test-repo')) do
            all("td.fetch.success").first
          end
        end

        binding.pry
      end
    end
  end
end
