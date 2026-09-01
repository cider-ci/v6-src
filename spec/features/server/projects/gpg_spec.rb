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

    # Isolated GPG keyring in /tmp (short path — gpg-agent socket has a 104-char limit)
    @gnupghome = Dir.mktmpdir('cig-', '/tmp')
    ENV['GNUPGHOME'] = @gnupghome

    # Start agent then generate a passwordless RSA key pair
    system('gpgconf', '--launch', 'gpg-agent', out: File::NULL, err: File::NULL)
    File.write(File.join(@gnupghome, 'k'), <<~BATCH)
      Key-Type: RSA
      Key-Length: 2048
      Name-Real: CI Test Signer
      Name-Email: signer@cider-ci.example
      Expire-Date: 0
      %no-protection
      %commit
    BATCH
    system('gpg', '--batch', '--gen-key', File.join(@gnupghome, 'k'),
           out: File::NULL, err: File::NULL)

    # Fingerprint in uppercase to match BouncyCastle hex-fingerprint output
    fpr_line = `gpg --with-colons --fingerprint signer@cider-ci.example 2>/dev/null`
                 .split("\n").find { |l| l.start_with?('fpr') }
    @fingerprint = fpr_line.split(':')[9].upcase
    @ascii_key   = `gpg --armor --export signer@cider-ci.example 2>/dev/null`

    # Signed git commit in a temp repo, cloned to a bare repo for git_url
    @work_dir = Dir.mktmpdir('cig-w-', '/tmp')
    @bare_dir = Dir.mktmpdir('cig-b-', '/tmp')
    repo = Git.init(@work_dir)
    repo.config('user.email', 'signer@cider-ci.example')
    repo.config('user.name', 'CI Test Signer')
    File.write(File.join(@work_dir, 'README.md'), '# GPG test')
    repo.add('README.md')
    repo.commit('Signed test commit', gpg_sign: true)
    system("git clone --bare #{@work_dir} #{@bare_dir}/repo.git",
           out: File::NULL, err: File::NULL)
    @git_url = "file://#{@bare_dir}/repo.git"
  end

  after :each do
    system('gpgconf', '--kill', 'gpg-agent', out: File::NULL, err: File::NULL)
    ENV.delete('GNUPGHOME')
    FileUtils.rm_rf(@gnupghome)
    FileUtils.rm_rf(@work_dir)
    FileUtils.rm_rf(@bare_dir)
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
