require 'factory_bot'
require 'faker'

Sequel::Model.db = database
Sequel::Model.send :alias_method, :save!, :save

RSpec.configure do |config|
  config.include FactoryBot::Syntax::Methods

  config.before(:suite) do
    FactoryBot.find_definitions
  end

  config.before(:each) do
    Faker::UniqueGenerator.clear
  end
end
