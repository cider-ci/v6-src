require 'gpgme'
require 'fileutils'

# this works
# commiting + signing via gpg works which set GNUPGHOME
# Problem: passphrase asking does practially not work in a CI setting
# so far no success setting an empty password

module GPG_USER

  def self.passfunc(hook, uid_hint, passphrase_info, prev_was_bad, fd)
    $stderr.write("Passphrase for #{uid_hint}: ")
    $stderr.flush
    begin
      system('stty -echo')
      io = IO.for_fd(fd, 'w')
      io.puts(gets)
      io.flush
    ensure
      (0 ... $_.length).each do |i| $_[i] = ?0 end if $_
      system('stty echo')
    end
    $stderr.puts
  end

  def self.progfunc(hook, what, type, current, total)
    $stderr.write("#{what}: #{current}/#{total}\r")
    $stderr.flush
  end

  def self.create_gpg_key(user)
    gpg_dir = Pathname.new(PROJECT_DIR.join('tmp',"#{user.login}_gpg"))
    FileUtils.rm_rf(gpg_dir)
    FileUtils.mkdir_p(gpg_dir)
    ENV['GNUPGHOME']=gpg_dir.to_s

    ctx = GPGME::Ctx.new(
      {:progress_callback => method(:progfunc),
       :passphrase_callback => method(:passfunc)})

    keyparams = <<~EOF
      <GnupgKeyParms format="internal">
      Key-Type: DSA
      Key-Length: 1024
      Subkey-Type: ELG-E
      Subkey-Length: 1024
      Name-Real: #{user.name}
      Name-Email: #{user.email_address}
      Expire-Date: 0
      Passphrase: abc
      </GnupgKeyParms>
    EOF

    ctx.genkey(keyparams, nil, nil)

    gpg_dir
  end
end
