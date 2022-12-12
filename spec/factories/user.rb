require Pathname.new(File.dirname(__FILE__)).join("users","gpg_key")

class User < Sequel::Model
  attr_accessor :password
  attr_accessor :firstname
  attr_accessor :lastname
  attr_accessor :session_token
  attr_accessor :email_address
  attr_accessor :gpg_home
end

FactoryBot.define do
  factory :user do
    firstname { Faker::Name.unique.first_name }
    lastname { Faker::Name.unique.last_name }
    name { firstname + ' ' + lastname }
    login { firstname }
    email_address  {firstname + '.' + lastname + '@' + Faker::Internet.domain_name }
    password { "secret" }
    session_token { SecureRandom.uuid }
    gpg_home { nil }

    after :create do |user|

      password_hash = database["SELECT crypt(#{database.literal(user.password)}, " \
                         "gen_salt('bf')) AS pw_hash"].first[:pw_hash]
      database[:passwords].insert(
        user_id: user.id,
        password_hash: password_hash)

      token_digest = database[
        "SELECT encode(digest(#{database.literal(user.session_token)},'sha256'), 'base64')" \
        "AS token_digest" ].first[:token_digest]
      database[:sessions].insert(
        user_id: user.id, token_digest: token_digest)

      database[:email_addresses].insert(
        user_id: user.id, email_address: user.email_address, is_primary: true)

      (1..3).each do |i|
        database[:email_addresses].insert(
          user_id: user.id,
          email_address: "#{user.firstname}_#{i}@#{user.lastname}.example",
          is_primary: false)
      end
    end

    trait :with_gpg_key do
      after :create do |user|
        user.gpg_home = GPG_USER.create_gpg_key(user)
      end
    end

    factory :admin do
      is_admin { true }
    end
  end
end
