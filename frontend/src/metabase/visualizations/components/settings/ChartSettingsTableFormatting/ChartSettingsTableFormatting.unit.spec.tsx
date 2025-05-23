import userEvent from "@testing-library/user-event";
import { useState } from "react";

import { render, screen } from "__support__/ui";
import type { ColumnFormattingSetting } from "metabase-types/api";
import { createMockColumn } from "metabase-types/api/mocks";

import {
  ChartSettingsTableFormatting,
  type ChartSettingsTableFormattingProps,
} from "./ChartSettingsTableFormatting";

const STRING_COLUMN = createMockColumn({
  base_type: "type/Text",
  display_name: "String Column",
  name: "STRING_COLUMN",
});

const STRING_COLUMN_TWO = createMockColumn({
  base_type: "type/Text",
  display_name: "String Column 2",
  name: "STRING_COLUMN_2",
});

const BOOLEAN_COLUMN = createMockColumn({
  base_type: "type/Boolean",
  display_name: "Boolean Column",
  name: "BOOLEAN_COLUMN",
});

const NUMBER_COLUMN = createMockColumn({
  base_type: "type/Integer",
  display_name: "Number Column",
  name: "NUMBER_COLUMN",
});

const PRIMARY_KEY_COLUMN = createMockColumn({
  base_type: "type/Integer",
  semantic_type: "type/PK",
  display_name: "Primary Key Column",
  name: "PK_COLUMN",
});

const FOREIGN_KEY_COLUMN = createMockColumn({
  base_type: "type/Integer",
  semantic_type: "type/FK",
  display_name: "Foreign Key Column",
  name: "FK_COLUMN",
});

const COLUMNS = [
  STRING_COLUMN,
  STRING_COLUMN_TWO,
  BOOLEAN_COLUMN,
  NUMBER_COLUMN,
  PRIMARY_KEY_COLUMN,
  FOREIGN_KEY_COLUMN,
];

const STRING_OPERATORS = [
  "is null",
  "is not null",
  "is equal to",
  "is not equal to",
  "contains",
  "does not contain",
  "starts with",
  "ends with",
];

const NUMBER_OPERATORS = [
  "is null",
  "is not null",
  "is equal to",
  "is not equal to",
  "is less than",
  "is greater than",
  "is less than or equal to",
  "is greater than or equal to",
];

const BOOLEAN_OPERATORS = ["is null", "is not null", "is true", "is false"];

const Wrapper = (props: Partial<ChartSettingsTableFormattingProps> = {}) => {
  const [value, setValue] = useState<ColumnFormattingSetting[]>([]);

  return (
    <ChartSettingsTableFormatting
      cols={COLUMNS}
      onChange={setValue}
      value={value}
      {...props}
    />
  );
};

const setup = (props = {}) => {
  render(<Wrapper {...props} />);
};

describe("ChartSettingsTableFormatting", () => {
  it("should allow you to add a rule", async () => {
    setup();
    expect(screen.getByText("Conditional formatting")).toBeInTheDocument();
    await userEvent.click(await screen.findByText("Add a rule"));
    await userEvent.click(await screen.findByText("String Column"));
    //Dismiss Popup
    await userEvent.click(
      await screen.findByText("Which columns should be affected?"),
    );

    expect(await screen.findByText("is equal to")).toBeInTheDocument();

    await userEvent.type(
      await screen.findByTestId("conditional-formatting-value-input"),
      "toucan",
    );
    await userEvent.click(await screen.findByText("Add rule"));

    expect(await screen.findByText("String Column")).toBeInTheDocument();
    expect(await screen.findByText(/is equal to toucan/)).toBeInTheDocument();
  });

  it("should only let you choose columns of the same type for a rule", async () => {
    setup();
    expect(screen.getByText("Conditional formatting")).toBeInTheDocument();
    await userEvent.click(await screen.findByText("Add a rule"));
    await userEvent.click(await screen.findByText("String Column"));

    expect(
      await screen.findByRole("option", { name: "Number Column" }),
    ).toHaveAttribute("data-combobox-disabled", "true");

    expect(
      await screen.findByRole("option", { name: "Boolean Column" }),
    ).toHaveAttribute("data-combobox-disabled", "true");

    expect(
      await screen.findByRole("option", { name: "String Column 2" }),
    ).not.toHaveAttribute("data-combobox-disabled");
  });

  describe("should show appropriate operators based on column selection", () => {
    beforeEach(async () => {
      setup();
      await userEvent.click(screen.getByText("Add a rule"));
    });

    it("string", async () => {
      await userEvent.click(screen.getByText("String Column"));
      //Dismiss Popup
      await userEvent.click(
        screen.getByText("Which columns should be affected?"),
      );

      await userEvent.click(
        screen.getByTestId("conditional-formatting-value-operator-button"),
      );

      STRING_OPERATORS.forEach((operator) => {
        expect(
          screen.getByRole("option", { name: operator }),
        ).toBeInTheDocument();
      });
    });

    it("number primary key (metabase#17448)(VIZ-379)", async () => {
      await userEvent.click(screen.getByText("Primary Key Column"));
      //Dismiss Popup
      await userEvent.click(
        screen.getByText("Which columns should be affected?"),
      );

      await userEvent.click(
        screen.getByTestId("conditional-formatting-value-operator-button"),
      );

      STRING_OPERATORS.forEach((operator) => {
        expect(
          screen.getByRole("option", { name: operator }),
        ).toBeInTheDocument();
      });
    });

    it("number foreign key (metabase#17448)(VIZ-379)", async () => {
      await userEvent.click(screen.getByText("Foreign Key Column"));
      //Dismiss Popup
      await userEvent.click(
        screen.getByText("Which columns should be affected?"),
      );

      await userEvent.click(
        screen.getByTestId("conditional-formatting-value-operator-button"),
      );

      STRING_OPERATORS.forEach((operator) => {
        expect(
          screen.getByRole("option", { name: operator }),
        ).toBeInTheDocument();
      });
    });

    it("number", async () => {
      await userEvent.click(screen.getByText("Number Column"));
      //Dismiss Popup
      await userEvent.click(
        screen.getByText("Which columns should be affected?"),
      );

      await userEvent.click(
        screen.getByTestId("conditional-formatting-value-operator-button"),
      );

      NUMBER_OPERATORS.forEach((operator) => {
        expect(
          screen.getByRole("option", { name: operator }),
        ).toBeInTheDocument();
      });

      // Quick check for color range option on numeric rules
      expect(screen.getByText("Formatting style")).toBeInTheDocument();
      expect(
        screen.getByRole("radio", { name: /single color/i }),
      ).toBeChecked();
      expect(
        screen.getByRole("radio", { name: /color range/i }),
      ).not.toBeChecked();
    });

    it("boolean", async () => {
      await userEvent.click(screen.getByText("Boolean Column"));
      //Dismiss Popup
      await userEvent.click(
        screen.getByText("Which columns should be affected?"),
      );

      await userEvent.click(
        screen.getByTestId("conditional-formatting-value-operator-button"),
      );

      BOOLEAN_OPERATORS.forEach((operator) => {
        expect(
          screen.getByRole("option", { name: operator }),
        ).toBeInTheDocument();
      });
    });
  });
});
