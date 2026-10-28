require 'fileutils'
require 'git'
require 'tmpdir'

STATIC_GPG_KEY = File.read(PROJECT_DIR.join('spec/fixtures/gpg/test_public_key.asc'))

# A passwordless GPG key in an isolated keyring and a bare git repository
# with one commit signed by it (git_url file://...). Sets @fingerprint
# (uppercase, as BouncyCastle), @ascii_key and @git_url; remove it with
# remove_gpg_signed_repo.
def create_gpg_signed_repo(email:, name: 'CI Test Signer')
  # short path: the gpg-agent socket has a 104-char limit
  @gnupghome = Dir.mktmpdir('cig-', '/tmp')
  ENV['GNUPGHOME'] = @gnupghome
  system('gpgconf', '--launch', 'gpg-agent', out: File::NULL, err: File::NULL)
  File.write(File.join(@gnupghome, 'k'), <<~BATCH)
    Key-Type: RSA
    Key-Length: 2048
    Name-Real: #{name}
    Name-Email: #{email}
    Expire-Date: 0
    %no-protection
    %commit
  BATCH
  system('gpg', '--batch', '--gen-key', File.join(@gnupghome, 'k'),
         out: File::NULL, err: File::NULL)
  fpr_line = `gpg --with-colons --fingerprint #{email} 2>/dev/null`
               .split("\n").find { |l| l.start_with?('fpr') }
  @fingerprint = fpr_line.split(':')[9].upcase
  @ascii_key   = `gpg --armor --export #{email} 2>/dev/null`

  @work_dir = Dir.mktmpdir('cig-w-', '/tmp')
  @bare_dir = Dir.mktmpdir('cig-b-', '/tmp')
  repo = Git.init(@work_dir)
  repo.config('user.email', email)
  repo.config('user.name', name)
  File.write(File.join(@work_dir, 'README.md'), '# GPG test')
  repo.add('README.md')
  repo.commit('Signed test commit', gpg_sign: true)
  system("git clone --bare #{@work_dir} #{@bare_dir}/repo.git",
         out: File::NULL, err: File::NULL)
  @git_url = "file://#{@bare_dir}/repo.git"
end

def remove_gpg_signed_repo
  system('gpgconf', '--kill', 'gpg-agent', out: File::NULL, err: File::NULL)
  ENV.delete('GNUPGHOME')
  [@gnupghome, @work_dir, @bare_dir].compact.each { |dir| FileUtils.rm_rf(dir) }
end
