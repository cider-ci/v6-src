require 'active_support/all'
require 'pry'

PROJECT_DIR = Pathname.new(__FILE__).join("../..").realdirpath

require 'config/database'
require 'config/factories'
require 'config/browser'
# require 'config/http_client'

require 'helpers/global'
# require 'helpers/user'



RSpec.configure do |config|

  config.include Helpers::Global
  # config.include Helpers::User

  config.before :each do
    srand 1
    Faker::Config.random = Random.new(1)
  end

  config.after(:each) do |example|
    # auto-pry after failures, except in CI!
    unless (ENV['CIDER_CI_TRIAL_ID'].present? ||
        ENV['NOPRY_ON_EXCEPTION'].presence.try{ YAML.load(self) })
      unless example.exception.nil?
        binding.pry if example.exception
      end
    end
  end
end

require 'require_all'
require_rel "/features/shared"
