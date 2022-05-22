require 'spec_helper'


feature 'Projects' do

  context 'As an admin user' do
    include_context :signed_in_as_an_admin

    scenario 'something' do
      binding.pry
    end

  end

end

