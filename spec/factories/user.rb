class User < Sequel::Model
  attr_accessor :password
  attr_accessor :firstname
  attr_accessor :lastname
end

FactoryBot.define do
  factory :user do
    firstname { Faker::Name.first_name }
    lastname { Faker::Name.unique.last_name }
    name { firstname + ' ' + lastname }
    email { firstname + '.' + lastname + '@' + Faker::Internet.domain_name }
    password { "secret" }

    after :create do |user|
      password_hash = database["SELECT crypt(#{database.literal(user.password)}, " \
                         "gen_salt('bf')) AS pw_hash"].first[:pw_hash]
      database[:passwords].insert(
        user_id: user.id,
        password_hash: password_hash)
    end

    factory :admin do
      is_admin { true }
    end
  end
end
