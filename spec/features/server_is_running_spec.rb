require 'spec_helper'


feature 'The application server is running' do
  scenario 'and serving webpages' do
    visit '/'
    binding.pry
  end
end
