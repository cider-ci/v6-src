require 'spec_helper'
require 'tmpdir'
require 'fileutils'
require 'git'
require 'timeout'
require 'securerandom'

feature 'GPG signature verification' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    @project_id = "gpg-test-#{SecureRandom.hex(4)}"

    create_gpg_signed_repo(email: 'signer@cider-ci.example')
  end

  after :each do
    remove_gpg_signed_repo
  end

  def wait_for_head_commit
    row = nil
    Timeout.timeout(30) do
      sleep 1 until (row = database[:commits]
                           .join(:branches, current_commit_id: :id)
                           .where(Sequel[:branches][:repository_id] => @project_id)
                           .select(Sequel[:commits][:id], Sequel[:commits][:signature_fingerprint])
                           .first)
    end
    row
  end

  context 'with a globally trusted key in the database before first fetch' do
    before :each do
      # Key must be in the DB before the server auto-fetches the repo
      database[:gpg_keys].insert(
        fingerprint: @fingerprint, name: 'CI Test Key', ascii_key: @ascii_key)
      database[:repositories].insert(
        id: @project_id, name: 'GPG Test', git_url: @git_url)
      reload_server_repository_state
    end

    scenario 'commit signed by a globally trusted key shows as verified' do
      head_row = wait_for_head_commit
      commit_id = head_row[:id]
      visit "/projects/#{@project_id}/commits/#{commit_id}"
      expect(page).to have_css('.alert-success', text: /Signed/)
      expect(page).to have_content('CI Test Key')
    end
  end

  context 'with no trusted key in the database' do
    before :each do
      database[:repositories].insert(
        id: @project_id, name: 'GPG Test', git_url: @git_url)
      reload_server_repository_state
    end

    scenario 'commit signed by an untrusted key shows a warning' do
      row = wait_for_head_commit

      visit "/projects/#{@project_id}/commits/#{row[:id]}"
      expect(page).to have_css('.alert-warning', text: /not trusted/)
    end
  end

end
