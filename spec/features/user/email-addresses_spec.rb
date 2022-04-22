require 'spec_helper'

feature 'User email addresses' do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
    @other_user = FactoryBot.create :user
  end

  context 'a admin' do

    before :each do
      set_session_cookie @admin
      visit "/users/#{@user.id}/email-addresses/"
    end

    example 'can manage email-addresss of an other user' do

      # add
      nea = 'new-email-address@example.com'
      visit "/users/#{@user.id}/email-addresses/"
      fill_in 'email_address', with: nea
      click_on 'Add'
      expect(page).to have_content 'Request SUCCESS'
      persisted = database[:email_addresses].where(user_id: @user.id).where(email_address: nea).first
      expect(persisted).to be
      expect(persisted[:is_primary]).to be false

      # set as primary
      li = find("li", text: 'new-email-address@example.com')
      within li do
        click_on 'Set as primary'
      end
      expect(page).to have_content 'Request SUCCESS'
      persisted = database[:email_addresses].where(user_id: @user.id).where(email_address: nea).first
      expect(persisted[:is_primary]).to be true

      # delete
      li = find("li", text: 'new-email-address@example.com')
      within li do
        click_on 'Delete'
      end
      expect(page).to have_content 'Request SUCCESS'
      expect(database[:email_addresses].where(user_id: @user.id).where(email_address: nea).first).not_to be

    end

  end

end
